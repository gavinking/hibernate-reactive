/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive.loader;

import org.hibernate.HibernateException;
import org.hibernate.dialect.pagination.LimitHelper;
import org.hibernate.engine.internal.TwoPhaseLoad;
import org.hibernate.engine.spi.*;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.*;
import org.hibernate.graph.spi.GraphImplementor;
import org.hibernate.internal.CoreLogging;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.loader.plan.exec.query.spi.NamedParameterContext;
import org.hibernate.loader.spi.AfterLoadAction;
import org.hibernate.persister.collection.CollectionPersister;
import org.hibernate.persister.entity.Loadable;
import org.hibernate.reactive.util.impl.CompletionStages;
import org.hibernate.transform.ResultTransformer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * A {@link ReactiveResultSetProcessor} intended to be used by {@link ReactiveLoaderBasedLoader} implementations.
 *
 * @author Gail Badner
 */
public class ReactiveLoaderBasedResultSetProcessor implements ReactiveResultSetProcessor {
	protected static final CoreMessageLogger LOG = CoreLogging.messageLogger(ReactiveLoaderBasedResultSetProcessor.class);

	private final ReactiveLoaderBasedLoader loader;
	private final Iterable<PreLoadEventListener> listeners;

	public ReactiveLoaderBasedResultSetProcessor(ReactiveLoaderBasedLoader loader) {
		this.loader = loader;
		this.listeners = loader
				.getFactory()
				.getServiceRegistry()
				.getService(EventListenerRegistry.class)
				.getEventListenerGroup(EventType.PRE_LOAD)
				.listeners();
	}

	/**
	 * This method is based on {@link Loader#processResultSet}
	 */
	@Override
	public CompletionStage<List<Object>> reactiveExtractResults(
			ResultSet rs,
			SharedSessionContractImplementor session,
			QueryParameters queryParameters,
			NamedParameterContext namedParameterContext,
			boolean returnProxies,
			boolean readOnly,
			ResultTransformer forcedResultTransformer,
			List<AfterLoadAction> afterLoadActionList) throws SQLException {
		final int entitySpan = loader.getEntityPersisters().length;
		final RowSelection rowSelection = queryParameters.getRowSelection();
		final int maxRows = LimitHelper.hasMaxRows( rowSelection ) ? rowSelection.getMaxRows() : Integer.MAX_VALUE;
		final boolean createSubselects = loader.isSubselectLoadingEnabled();
		final List<EntityKey[]> subselectResultKeys = createSubselects ? new ArrayList<>() : null;
		final List<Object> hydratedObjects = entitySpan == 0 ? null : new ArrayList<>(entitySpan * 10);

		final List<Object> results = loader.getRowsFromResultSet(
				rs,
				queryParameters,
				session,
				returnProxies,
				forcedResultTransformer,
				maxRows,
				hydratedObjects,
				subselectResultKeys );

		return CompletionStages.voidFuture()
				.thenCompose(v -> reactiveInitializeEntitiesAndCollections(
						hydratedObjects,
						rs,
						session,
						queryParameters.isReadOnly(session),
						afterLoadActionList))
				.thenAccept(v -> {
					if (createSubselects) {
						loader.createSubselects(subselectResultKeys, queryParameters, session);
					}
				})
				.thenApply(v -> results);
	}

	/**
	 * This method is based on {@link Loader#initializeEntitiesAndCollections}
	 */
	private CompletionStage<Void> reactiveInitializeEntitiesAndCollections(
			final List<Object> hydratedObjects,
			final Object resultSetId,
			final SharedSessionContractImplementor session,
			final boolean readOnly,
			List<AfterLoadAction> afterLoadActions) throws HibernateException {

		final CollectionPersister[] collectionPersisters = loader.getCollectionPersisters();
		if (collectionPersisters != null) {
			for (CollectionPersister collectionPersister : collectionPersisters) {
				if (collectionPersister.isArray()) {
					//for arrays, we should end the collection load before resolving
					//the entities, since the actual array instances are not instantiated
					//during loading
					//TODO: or we could do this polymorphically, and have two
					//      different operations implemented differently for arrays
					loader.endCollectionLoad(resultSetId, session, collectionPersister);
				}
			}
		}

		//important: reuse the same event instances for performance!
		final PreLoadEvent pre;
		final PostLoadEvent post;
		if ( session.isEventSource() ) {
			pre = new PreLoadEvent( (EventSource) session );
			post = new PostLoadEvent( (EventSource) session );
		}
		else {
			pre = null;
			post = null;
		}

		CompletionStage<Void> stage;
		if ( hydratedObjects != null && !hydratedObjects.isEmpty() ) {
			if ( LOG.isTraceEnabled() ) {
				long hydratedObjectsSize = hydratedObjects.stream().filter(Objects::nonNull).count();
				LOG.tracev("Total objects hydrated: {0}", hydratedObjectsSize);
			}

			GraphImplementor<?> fetchGraphLoadContextToRestore = session.getFetchGraphLoadContext();
			stage = CompletionStages.loop(
					hydratedObjects,
					hydratedObject -> {
				if ( hydratedObject == null ) {
					// This is a hack to signal that we're starting to process a new row
					session.setFetchGraphLoadContext( fetchGraphLoadContextToRestore );
					return CompletionStages.voidFuture(); //TODO: ugly!
				}
				else {
					return initializeEntity( hydratedObject, readOnly, session, pre, listeners );
				}
			} );
		}
		else {
			stage = CompletionStages.voidFuture();
		}

		return stage.thenAccept( v -> {
			if ( collectionPersisters != null ) {
				for ( CollectionPersister collectionPersister : collectionPersisters ) {
					if ( !collectionPersister.isArray() ) {
						//for sets, we should end the collection load after resolving
						//the entities, since we might call hashCode() on the elements
						//TODO: or we could do this polymorphically, and have two
						//      different operations implemented differently for arrays
						loader.endCollectionLoad( resultSetId, session, collectionPersister );
					}
				}
			}

			if ( hydratedObjects != null ) {
				for ( Object hydratedObject : hydratedObjects ) {
					if ( hydratedObject != null ) {
						TwoPhaseLoad.afterInitialize( hydratedObject, session );
					}
				}
			}

			// Until this entire method is refactored w/ polymorphism, postLoad was
			// split off from initializeEntity.  It *must* occur after
			// endCollectionLoad to ensure the collection is in the
			// persistence context.
			final PersistenceContext persistenceContext = session.getPersistenceContextInternal();
			if ( hydratedObjects != null && !hydratedObjects.isEmpty() ) {
				for ( Object hydratedObject : hydratedObjects ) {
					if ( hydratedObject != null ) {
						TwoPhaseLoad.postLoad( hydratedObject, session, post );
						if ( afterLoadActions != null ) {
							for ( AfterLoadAction afterLoadAction : afterLoadActions ) {
								final EntityEntry entityEntry = persistenceContext.getEntry( hydratedObject );
								if ( entityEntry == null ) {
									// big problem
									throw new HibernateException(
											"Could not locate EntityEntry immediately after two-phase load"
									);
								}
								Loadable persister = (Loadable) entityEntry.getPersister();
								afterLoadAction.afterLoad( session, hydratedObject, persister );
							}
						}
					}
				}
			}
		} );
	}
}

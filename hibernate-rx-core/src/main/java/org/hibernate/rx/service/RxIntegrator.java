package org.hibernate.rx.service;

import org.hibernate.boot.Metadata;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.rx.event.DefaultRxFlushEventListener;
import org.hibernate.rx.event.DefaultRxLoadEventListener;
import org.hibernate.rx.event.DefaultRxPersistEventListener;
import org.hibernate.rx.event.DefaultRxPersistOnFlushEventListener;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;

/**
 * Integrates Hibernate Reactive with Hibernate ORM
 */
public class RxIntegrator implements Integrator {
	@Override
	public void integrate(
			Metadata metadata,
			SessionFactoryImplementor sessionFactory,
			SessionFactoryServiceRegistry serviceRegistry) {
		attachEventContextManagingListenersIfRequired( serviceRegistry );
	}

	@Override
	public void disintegrate(
			SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {
	}

	private void attachEventContextManagingListenersIfRequired(SessionFactoryServiceRegistry serviceRegistry) {
		EventListenerRegistry eventListenerRegistry = serviceRegistry.getService( EventListenerRegistry.class );

		eventListenerRegistry.addDuplicationStrategy( DefaultRxFlushEventListener.EventContextManagingFlushEventListenerDuplicationStrategy.INSTANCE );
		eventListenerRegistry.getEventListenerGroup( EventType.FLUSH ).appendListener( new DefaultRxFlushEventListener() );

		eventListenerRegistry.addDuplicationStrategy( DefaultRxLoadEventListener.EventContextManagingLoadEventListenerDuplicationStrategy.INSTANCE );
		eventListenerRegistry.getEventListenerGroup( EventType.LOAD ).appendListener( new DefaultRxLoadEventListener() );

		eventListenerRegistry.addDuplicationStrategy( DefaultRxPersistEventListener.EventContextManagingPersistEventListenerDuplicationStrategy.INSTANCE );
		eventListenerRegistry.getEventListenerGroup( EventType.PERSIST ).appendListener( new DefaultRxPersistEventListener() );
		eventListenerRegistry.getEventListenerGroup( EventType.PERSIST_ONFLUSH ).appendListener( new DefaultRxPersistOnFlushEventListener() );
	}

}
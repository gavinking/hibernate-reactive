/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive;

import io.vertx.ext.unit.TestContext;
import org.hibernate.Hibernate;
import org.hibernate.cfg.Configuration;
import org.junit.Test;

import javax.persistence.Basic;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.ManyToMany;
import javax.persistence.Table;
import java.util.HashSet;
import java.util.Set;

public class ManyToManyTest extends BaseReactiveTest {

    @Override
    protected Configuration constructConfiguration() {
        Configuration configuration = super.constructConfiguration();
        configuration.addAnnotatedClass( Book.class );
        configuration.addAnnotatedClass( Author.class );
        return configuration;
    }

    @Test
    public void test(TestContext context) {
        Book book1 = new Book("Feersum Endjinn");
        Book book2 = new Book("Use of Weapons");
        Author author = new Author("Iain M Banks");
        book1.authors.add(author);
        book2.authors.add(author);
        author.books.add(book1);
        author.books.add(book2);

        test(context,
                getMutinySessionFactory()
                        .withTransaction( (session, transaction) -> session.persistAll(book1, book2, author) )
                        .chain( () -> getMutinySessionFactory()
                                .withTransaction( (session, transaction) -> session.find(Book.class, book1.id)
                                        .invoke( b -> context.assertFalse( Hibernate.isInitialized(b.authors) ) )
                                        .chain( b -> session.fetch(b.authors) )
                                        .invoke( authors -> context.assertEquals( 1, authors.size() ) )
                                )
                        )
                        .chain( () -> getMutinySessionFactory()
                                .withTransaction( (session, transaction) -> session.find(Author.class, author.id)
                                        .invoke( a -> context.assertFalse( Hibernate.isInitialized(a.books) ) )
                                        .chain( a -> session.fetch(a.books) )
                                        .invoke( books -> context.assertEquals( 2, books.size() ) )
                                )
                        )
                        .chain( () -> getMutinySessionFactory()
                                .withTransaction( (session, transaction) -> session.createQuery("select distinct a from Author a left join fetch a.books", Author.class )
                                        .getSingleResult()
                                        .invoke( a -> context.assertTrue( Hibernate.isInitialized(a.books) ) )
                                        .invoke( a -> context.assertEquals( 2, a.books.size() ) )
                                )
                        )
        );
    }

    @Entity(name="Book")
    @Table(name="MTMBook")
    static class Book {
        Book(String name) {
            this.name = name;
        }
        Book() {}
        @GeneratedValue @Id long id;

        @Basic(optional = false)
        String name;

        @ManyToMany
        Set<Author> authors = new HashSet<>();
    }

    @Entity(name="Author")
    @Table(name="MTMAuthor")
    static class Author {
        Author(String title) {
            this.title = title;
        }
        public Author() {}
        @GeneratedValue @Id long id;

        @Basic(optional = false)
        String title;

        @ManyToMany(mappedBy = "authors")
        Set<Book> books = new HashSet<>();
    }
}
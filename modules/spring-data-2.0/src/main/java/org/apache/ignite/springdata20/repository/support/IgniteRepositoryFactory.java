/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ignite.springdata20.repository.support;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.springdata20.repository.IgniteRepository;
import org.apache.ignite.springdata20.repository.config.Query;
import org.apache.ignite.springdata20.repository.config.RepositoryConfig;
import org.apache.ignite.springdata20.repository.query.IgniteQuery;
import org.apache.ignite.springdata20.repository.query.IgniteQueryGenerator;
import org.apache.ignite.springdata20.repository.query.IgniteRepositoryQuery;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationContext;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.data.repository.core.EntityInformation;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.AbstractEntityInformation;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;
import org.springframework.data.repository.query.EvaluationContextProvider;
import org.springframework.data.repository.query.QueryLookupStrategy;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Crucial for spring-data functionality class. Create proxies for repositories.
 */
public class IgniteRepositoryFactory extends RepositoryFactorySupport {
    /** Ignite instance */
    private Ignite ignite;

    /** Spring application context */
    private ApplicationContext ctx;

    /** Mapping of a repository to a cache. */
    private final Map<Class<?>, String> repoToCache = new HashMap<>();

    /**
     * Creates the factory with initialized {@link Ignite} instance.
     *
     * @param ignite
     */
    public IgniteRepositoryFactory(Ignite ignite, ApplicationContext ctx) {
        this.ignite = ignite;
        this.ctx = ctx;
    }

    /**
     * Initializes the factory with provided {@link IgniteConfiguration} that is used to start up an underlying
     * {@link Ignite} instance.
     *
     * @param cfg Ignite configuration.
     */
    public IgniteRepositoryFactory(IgniteConfiguration cfg, ApplicationContext ctx) {
        this.ignite = Ignition.start(cfg);
        this.ctx = ctx;
    }

    /**
     * Initializes the factory with provided a configuration under {@code springCfgPath} that is used to start up
     * an underlying {@link Ignite} instance.
     *
     * @param springCfgPath A path to Ignite configuration.
     */
    public IgniteRepositoryFactory(String springCfgPath, ApplicationContext ctx) {
        this.ignite = Ignition.start(springCfgPath);
        this.ctx = ctx;
    }

    /** {@inheritDoc} */
    @Override public <T, ID> EntityInformation<T, ID> getEntityInformation(Class<T> domainClass) {
        return new AbstractEntityInformation<T, ID>(domainClass) {
            @Override public ID getId(T entity) {
                return null;
            }

            @Override public Class<ID> getIdType() {
                return null;
            }
        };
    }

    /** {@inheritDoc} */
    @Override protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
        return IgniteRepositoryImpl.class;
    }

    /** {@inheritDoc} */
    @Override protected RepositoryMetadata getRepositoryMetadata(Class<?> repoItf) {
        Assert.notNull(repoItf, "Repository interface must be set.");
        Assert.isAssignable(IgniteRepository.class, repoItf, "Repository must implement IgniteRepository interface.");

        RepositoryConfig annotation = repoItf.getAnnotation(RepositoryConfig.class);

        Assert.notNull(annotation, "Set a name of an Apache Ignite cache using @RepositoryConfig annotation to map " +
            "this repository to the underlying cache.");

        Assert.hasText(annotation.cacheName(), "Set a name of an Apache Ignite cache using @RepositoryConfig " +
            "annotation to map this repository to the underlying cache.");
        String cacheName = annotation.cacheName();
        if (isSpelExpression(annotation.cacheName()))
            cacheName = executeExpression(annotation.cacheName());

        repoToCache.put(repoItf, cacheName);

        return super.getRepositoryMetadata(repoItf);
    }

    /**
     *  execute a SpEL extression
     *
     * @param spelExpression SpEL expression
     * @return the result of execution of the SpEL expression
     */
    @NotNull private String executeExpression(String  spelExpression) {
        SpelExpressionParser parser = new SpelExpressionParser();
        Expression expression = parser.parseExpression(spelExpression);
        StandardEvaluationContext ec = new StandardEvaluationContext();
        ec.setBeanResolver(new BeanFactoryResolver(ctx.getAutowireCapableBeanFactory()));
        return (String)expression.getValue(ec);
    }

    /**
     * The method tryes to identify that the expression looks like SpEL extression
     *
     * @param expression string with a expression
     * @return true if the string contains attributes of SpEL expression
     * @see <a href="https://docs.spring.io/spring/docs/5.0.16.RELEASE/spring-framework-reference/core.html#expressions">SpEL</a>
     */
    private boolean isSpelExpression(String expression) {
        return expression.contains("@") || expression.contains("#")
            || expression.contains("$") || expression.contains("?") || expression.contains("(")
            || expression.contains(")") || expression.contains("'") || expression.contains("{")
            || expression.contains("}") || expression.contains(">") || expression.contains("<")
            || expression.contains("=");
    }

    /** {@inheritDoc} */
    @Override protected Object getTargetRepository(RepositoryInformation metadata) {
        return getTargetRepositoryViaReflection(metadata,
            ignite.getOrCreateCache(repoToCache.get(metadata.getRepositoryInterface())));
    }

    /** {@inheritDoc} */
    @Override protected Optional<QueryLookupStrategy> getQueryLookupStrategy(final QueryLookupStrategy.Key key,
        EvaluationContextProvider evaluationCtxProvider) {
        return Optional.of((mtd, metadata, factory, namedQueries) -> {

            final Query annotation = mtd.getAnnotation(Query.class);

            if (annotation != null) {
                String qryStr = annotation.value();

                if (key != QueryLookupStrategy.Key.CREATE && StringUtils.hasText(qryStr)) {
                    return new IgniteRepositoryQuery(metadata,
                            new IgniteQuery(qryStr, isFieldQuery(qryStr), IgniteQueryGenerator.getOptions(mtd)),
                            mtd, factory, ignite.getOrCreateCache(repoToCache.get(metadata.getRepositoryInterface())));
                }
            }

            if (key == QueryLookupStrategy.Key.USE_DECLARED_QUERY) {
                throw new IllegalStateException("To use QueryLookupStrategy.Key.USE_DECLARED_QUERY, pass " +
                        "a query string via org.apache.ignite.springdata.repository.config.Query annotation.");
            }

            return new IgniteRepositoryQuery(metadata, IgniteQueryGenerator.generateSql(mtd, metadata), mtd,
                factory, ignite.getOrCreateCache(repoToCache.get(metadata.getRepositoryInterface())));
        });
    }

    /**
     * @param qry Query string.
     * @return {@code true} if query is SqlFieldsQuery.
     */
    private boolean isFieldQuery(String qry) {
        return isStatement(qry) && !qry.matches("^SELECT\\s+(?:\\w+\\.)?+\\*.*");
    }

    /**
     * Evaluates if the query starts with a clause.<br>
     * <code>SELECT, INSERT, UPDATE, MERGE, DELETE</code>
     *
     * @param qry Query string.
     * @return {@code true} if query is full SQL statement.
     */
    private boolean isStatement(String qry) {
        return qry.matches("^SELECT.*") || qry.matches("^UPDATE.*") || qry.matches("^DELETE.*") ||
            qry.matches("^MERGE.*") || qry.matches("^INSERT.*");
    }
}

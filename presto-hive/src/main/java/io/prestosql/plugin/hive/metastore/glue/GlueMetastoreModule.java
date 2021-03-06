/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.plugin.hive.metastore.glue;

import com.amazonaws.handlers.RequestHandler2;
import com.google.inject.Binder;
import com.google.inject.Key;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import io.airlift.concurrent.BoundedExecutor;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.prestosql.plugin.base.CatalogName;
import io.prestosql.plugin.hive.ForRecordingHiveMetastore;
import io.prestosql.plugin.hive.HiveConfig;
import io.prestosql.plugin.hive.metastore.HiveMetastore;
import io.prestosql.plugin.hive.metastore.RecordingHiveMetastoreModule;
import io.prestosql.plugin.hive.metastore.WriteHiveMetastoreRecordingProcedure;
import io.prestosql.plugin.hive.metastore.cache.CachingHiveMetastoreModule;
import io.prestosql.plugin.hive.metastore.cache.ForCachingHiveMetastore;
import io.prestosql.spi.procedure.Procedure;

import java.util.concurrent.Executor;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.inject.multibindings.Multibinder.newSetBinder;
import static com.google.inject.multibindings.OptionalBinder.newOptionalBinder;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static org.weakref.jmx.guice.ExportBinder.newExporter;

public class GlueMetastoreModule
        extends AbstractConfigurationAwareModule
{
    @Override
    protected void setup(Binder binder)
    {
        configBinder(binder).bindConfig(GlueHiveMetastoreConfig.class);

        newOptionalBinder(binder, GlueColumnStatisticsProvider.class)
                .setDefault().to(DisabledGlueColumnStatisticsProvider.class).in(Scopes.SINGLETON);

        newOptionalBinder(binder, Key.get(RequestHandler2.class, ForGlueHiveMetastore.class));

        if (buildConfigObject(HiveConfig.class).getRecordingPath() != null) {
            binder.bind(HiveMetastore.class)
                    .annotatedWith(ForRecordingHiveMetastore.class)
                    .to(GlueHiveMetastore.class)
                    .in(Scopes.SINGLETON);
            binder.bind(GlueHiveMetastore.class).in(Scopes.SINGLETON);
            newExporter(binder).export(GlueHiveMetastore.class).withGeneratedName();
            binder.install(new RecordingHiveMetastoreModule());

            Multibinder<Procedure> procedures = newSetBinder(binder, Procedure.class);
            procedures.addBinding().toProvider(WriteHiveMetastoreRecordingProcedure.class).in(Scopes.SINGLETON);
        }
        else {
            binder.bind(HiveMetastore.class)
                    .annotatedWith(ForCachingHiveMetastore.class)
                    .to(GlueHiveMetastore.class)
                    .in(Scopes.SINGLETON);
            newExporter(binder).export(HiveMetastore.class)
                    .as(generator -> generator.generatedNameOf(GlueHiveMetastore.class));
        }
        binder.install(new CachingHiveMetastoreModule());
    }

    @Provides
    @Singleton
    @ForGlueHiveMetastore
    public Executor createExecutor(CatalogName catalogName, GlueHiveMetastoreConfig hiveConfig)
    {
        if (hiveConfig.getGetPartitionThreads() == 1) {
            return directExecutor();
        }
        return new BoundedExecutor(
                newCachedThreadPool(daemonThreadsNamed("hive-glue-%s")),
                hiveConfig.getGetPartitionThreads());
    }
}

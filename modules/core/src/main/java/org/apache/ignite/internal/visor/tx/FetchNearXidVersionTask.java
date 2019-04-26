/*
 * Copyright 2019 GridGain Systems, Inc. and Contributors.
 *
 * Licensed under the GridGain Community Edition License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.gridgain.com/products/software/community-edition/gridgain-community-edition-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ignite.internal.visor.tx;

import java.util.Collection;
import java.util.List;
import org.apache.ignite.IgniteException;
import org.apache.ignite.compute.ComputeJobResult;
import org.apache.ignite.internal.processors.cache.transactions.IgniteInternalTx;
import org.apache.ignite.internal.processors.cache.transactions.IgniteTxManager;
import org.apache.ignite.internal.processors.cache.version.GridCacheVersion;
import org.apache.ignite.internal.processors.task.GridInternal;
import org.apache.ignite.internal.visor.VisorJob;
import org.apache.ignite.internal.visor.VisorMultiNodeTask;
import org.jetbrains.annotations.Nullable;

/**
 * Retrieves unique transaction identifier (nearXid) from UUID/GridCacheVersion of xid/nearXid.
 */
@GridInternal
public class FetchNearXidVersionTask extends VisorMultiNodeTask<TxVerboseId, GridCacheVersion, GridCacheVersion> {
    /** */
    private static final long serialVersionUID = 0L;

    /** {@inheritDoc} */
    @Override protected VisorJob<TxVerboseId, GridCacheVersion> job(TxVerboseId arg) {
        return new FetchNearXidVersionJob(arg, debug);
    }

    /** {@inheritDoc} */
    @Override protected GridCacheVersion reduce0(List<ComputeJobResult> results) throws IgniteException {
        for (ComputeJobResult res : results) {
            if (res.getData() != null)
                return res.getData();
        }

        return null;
    }

    /**
     *
     */
    private static class FetchNearXidVersionJob extends VisorJob<TxVerboseId, GridCacheVersion> {
        /** */
        private static final long serialVersionUID = 0L;

        /**
         * @param arg Argument.
         * @param debug Debug.
         */
        public FetchNearXidVersionJob(TxVerboseId arg, boolean debug) {
            super(arg, debug);
        }

        /** {@inheritDoc} */
        @Override protected GridCacheVersion run(@Nullable TxVerboseId arg) throws IgniteException {
            IgniteTxManager tm = ignite.context().cache().context().tm();

            Collection<IgniteInternalTx> transactions = tm.activeTransactions();

            for (IgniteInternalTx tx : transactions) {
                if (tx.xid().equals(arg.uuid()) ||
                    tx.nearXidVersion().asGridUuid().equals(arg.uuid()) ||
                    tx.xidVersion().equals(arg.gridCacheVersion()) ||
                    tx.nearXidVersion().equals(arg.gridCacheVersion()))
                    return tx.nearXidVersion();
            }

            return null;
        }
    }
}
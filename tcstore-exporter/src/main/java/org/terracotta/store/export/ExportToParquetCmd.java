/*
 * Copyright (c) 2020 Software AG, Darmstadt, Germany and/or its licensors
 *
 * SPDX-License-Identifier: Apache-2.0
 *
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
package org.terracotta.store.export;

import com.terracottatech.store.Type;
import com.terracottatech.store.manager.DatasetManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;

public class ExportToParquetCmd
{
    private static final Logger LOG = LoggerFactory.getLogger(ExportToParquetCmd.class);

    public static void main(String[] args)
    {
        try {
            CmdLineOptions cmd = new CmdLineOptions(args);
            if (cmd.getShowHelp()) {
                // display usage and help information
                LOG.info(cmd.getHelp());
                return;
            }

            String errors = cmd.Validate();
            if (!errors.isEmpty()) {
                LOG.error("Errors were encountered:\n" + errors);
                LOG.info(cmd.getHelp());
                return;
            }

            String uri = cmd.getServerUri();
            String datasetName = cmd.getDatasetName();
            Type datasetType = cmd.getType(cmd.getDatasetType());
            String outputFileFolder = cmd.getOutput();

            ParquetOptions options = new ParquetOptions();
            options.setSchemaSampleSize(cmd.getSchemaSampleSize());
            options.setAppendTypeToSchemaFieldName(cmd.getAppendTypeToSchemaFieldName());
            if (cmd.getUseFilterRange())
                options.setFilterCell(cmd.getFilterCellName(), cmd.getType(cmd.getFilterCellType()));
            options.setDoNotAbortIfFilterCellMissing(cmd.getDoNotAbortExportIfFilterCellMissing());
            options.setMaxOutputColumns(cmd.getMaxOutputColumns());
            options.setMaxOutputColumnsNoAbort(cmd.getMaxOutputColumnsNoAbort());
            options.setMaxOutputColumnsUseMultiFile(cmd.getMaxOutputColumnsUseMultiFile());
            options.setLogStreamPlan(cmd.getLogStreamPlan());
            options.setMaxStringLength(cmd.getMaxStringLength());
            options.setMaxByteArraySize(cmd.getMaxByteArraySize());
            List<String> whiteListCells = cmd.getWhiteListCellsNamesTypes();
            for (int i = 0; i < cmd.getWhiteListCellsNamesTypes().size(); i++)
                options.addWhiteListCell(whiteListCells.get(i), cmd.getType(whiteListCells.get(++i)));
            List<String> blackListCells = cmd.getBlackListCellsNamesTypes();
            for (int i = 0; i < blackListCells.size(); i++)
                options.addBlackListCell(blackListCells.get(i), cmd.getType(blackListCells.get(++i)));

            LOG.info("Connecting to server: '" + uri + "'");
            try (DatasetManager dsManager = DatasetManager.clustered((new URI(uri))).build()) {
                try {
                    DatasetParquetFileExporter exporter = new DatasetParquetFileExporter(dsManager, datasetName, datasetType, outputFileFolder, options);
                    if (cmd.getUseFilterRange())
                        exporter.exportDataset(cmd.getFilterLowValue(), cmd.getFilterHighValue());
                    else
                        exporter.exportDataset();
                }
                catch (Exception ex) {
                    LOG.warn("Exception creating/exporting dataset to parquet file: " + ex.getMessage());
                }
            }
            catch (Exception ex) {
                LOG.error("Exception creating Dataset Manager for parquet file export: " + ex.getMessage());
            }
        }
        catch (Exception ex) {
            LOG.error("Exception processing data for parquet file export: " + ex.getMessage());
        }
    }
}

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

import com.terracottatech.store.Cell;
import com.terracottatech.store.Dataset;
import com.terracottatech.store.Record;
import com.terracottatech.store.Type;
import com.terracottatech.store.definition.CellDefinition;
import com.terracottatech.store.manager.DatasetManager;
import com.terracottatech.store.stream.RecordStream;
import org.apache.avro.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

public class ParquetSchema {

    private static final Logger LOG = LoggerFactory.getLogger(ParquetSchema.class);
    public static final String REC_KEY = "RecKey";

    private DatasetManager dsManager;
    private ParquetOptions options;
    private String datasetName;
    private Type datasetType;

    private List<Schema> schemas;
    private Boolean usingFilterCell = false;
    private Map<CellDefinition<?>, String> uniqueFieldNames;
    private Map<CellDefinition<?>, Schema> cellDefinitionSchemaMap;

    ParquetSchema(DatasetManager dsManager, String datasetName, Type datasetType, ParquetOptions options) throws StoreExportException
    {
        this.dsManager = requireNonNull(dsManager);
        this.options = requireNonNull(options);
        this.datasetName = datasetName;
        this.datasetType = datasetType;
        uniqueFieldNames = new HashMap<>();
        try {
            initialize();
        }
        catch (StoreExportException se){
            String status = "Dataset Export to Parquet was Aborted.";
            LOG.warn(status, se);
            throw new StoreExportException(status, se);
        }
    }

    private void initialize() throws StoreExportException {
        // Schema initialization must be completed before any dataset export can happen.
        // - sample the target dataset for all unique cells
        // - modify the uniqe cells based on whitelisted and blacklisted cells
        // - determine how many schemas must be created based on configured 'max columns' option
        // - handle aborting the initialization based on configured 'ignore abort' option
        // - create an Avro schema per set of partitioned dataset cells
        // - populate maps so that the ParquetWriter can determine to which file/schema each
        //   cell in each streamed record is to be written.

        schemas = new ArrayList<>();
        cellDefinitionSchemaMap = new HashMap<>();

        try (Dataset<?> dataset = dsManager.getDataset(this.datasetName, this.datasetType)) {
            Set<CellDefinition<?>> uniqueCells;
            try (RecordStream<?> records = dataset.reader().records()) {
                uniqueCells = records
                    .limit(options.getSchemaSampleSize())
                    .flatMap(Record::stream)
                    .map(Cell::definition)
                    .collect(Collectors.toSet());
            }
            // We have a unique list of all cells present in the dataset (for the given record sample)
            // Modify this list as required based on the specified allowed/disallowed (whitelist/blacklist) cells

            Set<CellDefinition<?>> whiteList = options.getWhiteListCells();
            Set<CellDefinition<?>> blackList = options.getBlackListCells();
            if (whiteList.size() > 0) {
                LOG.info("Constraining the schema to ONLY include 'whitelisted' cells:\n" + whiteList.toString());
                uniqueCells.retainAll(whiteList);
            }
            else if (blackList.size() > 0) {
                LOG.info("Restricting 'blacklisted' cells from appearing in the schema:\n" + blackList.toString());
                uniqueCells.removeAll(blackList);
            }
            // Does the specified filter cell exist in this dataset
            usingFilterCell = uniqueCells.contains(options.getFilterCell());

            // Now create one or more schemas for our target parquet files
            boolean useSingleSchema = true;
            int columnCount = uniqueCells.size() + 1; // add one for the mandatory REC_KEY
            Integer maxColumns = options.getMaxOutputColumns();
            if (columnCount > maxColumns) {
                LOG.info("The number of unique cells (" + columnCount + ") is greater than the maximum allowed (" + maxColumns + ") parquet columns.");
                if (options.getMaxOutputColumnsNoAbort()) {
                    LOG.info("'Ignore Abort' option has been set.  Exporting dataset will continue.");
                }
                else {
                    LOG.info("'Ignore Abort' option has NOT been set.  Aborting.");
                    throw new StoreExportException("The number of unique cells (" + columnCount + ") is greater than the maximum allowed (" + maxColumns + ") parquet columns.");
                }
                if (options.getMaxOutputColumnsUseMultiFile()) {
                    // split cells into multiple schemas
                    useSingleSchema = false;
                }
            }

            if (useSingleSchema) {
                // Since uniqueCells is not sorted, check if the filter cell exists then add
                // that at index 1 (after the REC_KEY at index 0) of a new sorted set (just to make
                // viewing the contents of the parquet pretty).

                Set<CellDefinition<?>> sortedUniqueCells = new LinkedHashSet<>(); //preserves insertion ordering
                sortedUniqueCells.add(CellDefinition.define(REC_KEY, datasetType)); // 'RecKey' is always needed
                if (usingFilterCell)
                    sortedUniqueCells.add(options.getFilterCell());
                sortedUniqueCells.addAll(uniqueCells);
                Schema schema = createSchema(sortedUniqueCells);
                schemas.add(schema);
                for (CellDefinition<?> cell : sortedUniqueCells)
                    cellDefinitionSchemaMap.put(cell, schema);
            }
            else {
                // We're going to create multiple schemas to generate multiple parquet files
                int cellCount = maxColumns; // to trigger Set creation in for loop
                List<Set<CellDefinition<?>>> setList = new ArrayList<>();
                Set<CellDefinition<?>> sortedUniqueCells = null;
                for (CellDefinition<?> cell : uniqueCells) {
                    if (cellCount >= maxColumns) {
                        // create a new set for this chunk of cells
                        sortedUniqueCells = new LinkedHashSet<>(); //preserves insertion ordering
                        sortedUniqueCells.add(CellDefinition.define(REC_KEY, datasetType)); // 'RecKey' is always needed
                        if (usingFilterCell)
                            sortedUniqueCells.add(options.getFilterCell());

                        setList.add(sortedUniqueCells);
                    }
                    sortedUniqueCells.add(cell);
                    cellCount = sortedUniqueCells.size();
                }
                for (Set<CellDefinition<?>> cellSet : setList) {
                    Schema schema = createSchema(cellSet);
                    schemas.add(schema);
                    for (CellDefinition<?> cell : cellSet)
                        cellDefinitionSchemaMap.put(cell, schema);
                }
            }
        }
        catch (Exception ex) {
            LOG.error("Exception initializing schema from dataset: ", ex);
            throw new StoreExportException("Exception initializing schema from dataset: " + ex.getMessage(), ex);
        }
    }

    private Schema createSchema(Set<CellDefinition<?>> uniqueCells)
    {
        // Avro format
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\": \"record\",\n");
        sb.append(" \"name\": \"" + datasetName + "\",\n");
        sb.append(" \"fields\": [ \n");
        for (CellDefinition<?> cell : uniqueCells) {

            String fieldName = getUniqueFieldName(cell, uniqueCells);
            uniqueFieldNames.put(cell, fieldName); // used during file write

            sb.append(" { \"name\": \"" + fieldName + "\", \"type\": ");
            if (fieldName.equals(REC_KEY)) {
                sb.append("\"" + getAvroType(cell.type()) + "\" },\n");  // nulls not allowed
            }
            else {
                sb.append("[\"" + getAvroType(cell.type()) + "\", \"null\"] },\n"); // nulls allowed
            }
        }
        String sbString = sb.toString();
        String schemaString = sbString.substring(0, sbString.length()-2) + "\n]}\n";  // trim the last ','
        LOG.info("Schema extracted from dataset sampling " + options.getSchemaSampleSize().toString() + " records:\n" + schemaString);
        Schema.Parser parser = new Schema.Parser().setValidate(true);
        return parser.parse(schemaString);
    }

    private String getUniqueFieldName(CellDefinition<?> cell, Set<CellDefinition<?>> cells) {

        // Returns a unique schema Field name based on the cell definition name and cell type.
        // When same-named cells are found in the set, the field name becomes <name>_<type>.
        // When the cell name is unique, the field name is simply the cell name

        String fieldName;
        if (cell.name().equals(REC_KEY) ||
            cell.name().equals(options.getFilterCellName()) ||
            (!options.getAppendTypeToSchemaFieldName() && cells.stream().filter(s -> s.name().equals(cell.name())).count() == 1)) {
            fieldName = cell.name();
        }
        else {
            fieldName = String.format("%s_%s", cell.name(), getTcType(cell.type()) );
        }
        return fieldName;
    }

    private String getAvroType(Type type)
    {
        String avroType;
        if (type.equals(Type.BOOL))
            avroType = "boolean";
        else if (type.equals(Type.CHAR))
            avroType = "string";
        else if (type.equals(Type.INT))
            avroType = "int";
        else if (type.equals(Type.LONG))
            avroType = "long";
        else if (type.equals(Type.DOUBLE))
            avroType = "double";
        else if (type.equals(Type.STRING))
            avroType = "string";
        else if (type.equals(Type.BYTES))
            avroType = "bytes";
        else
            avroType = "null";
        return avroType;
    }

    public String getTcType(Type type)
    {
        String tcType;
        if (type.equals(Type.BOOL))
            tcType = "BOOLEAN";
        else if (type.equals(Type.CHAR))
            tcType = "CHAR";
        else if (type.equals(Type.INT))
            tcType = "INT";
        else if (type.equals(Type.LONG))
            tcType = "LONG";
        else if (type.equals(Type.DOUBLE))
            tcType = "DOUBLE";
        else if (type.equals(Type.STRING))
            tcType = "STRING";
        else if (type.equals(Type.BYTES))
            tcType = "BYTES";
        else
            tcType = "";
        return tcType;
    }

    public String getFieldName(CellDefinition<?> cell) {
        return uniqueFieldNames.get(cell);
    }

    public Schema getSchema(CellDefinition<?> cell) {
        return cellDefinitionSchemaMap.get(cell);
    }

    public List<Schema> getSchemas() {
        return schemas;
    }

    public Boolean getUsingFilterCell() {
        return usingFilterCell;
    }
}
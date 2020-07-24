/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2020 DBeaver Corp and others
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
package org.jkiss.dbeaver.tools.transfer.stream.exporter;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPNamedObject;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDContent;
import org.jkiss.dbeaver.model.data.DBDContentStorage;
import org.jkiss.dbeaver.model.data.DBDContentValueHandler;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLConstants;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.tools.transfer.DTUtils;
import org.jkiss.dbeaver.tools.transfer.stream.IStreamDataExporterSite;
import org.jkiss.dbeaver.utils.ContentUtils;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;

/**
 * SQL Exporter
 */
public class DataExporterSQL extends StreamExporterAbstract {

    private static final Log log = Log.getLog(DataExporterSQL.class);

    private static final String PROP_INCLUDE_AUTO_GENERATED = "includeAutoGenerated";
    private static final String PROP_OMIT_SCHEMA = "omitSchema";
    private static final String PROP_ROWS_IN_STATEMENT = "rowsInStatement";
    private static final char STRING_QUOTE = '\'';

    private boolean includeAutoGenerated;
    private String rowDelimiter;
    private boolean omitSchema;
    private int rowsInStatement;
    private String tableName;
    private DBDAttributeBinding[] columns;

    private transient StringBuilder sqlBuffer = new StringBuilder(100);
    private transient long rowCount;
    private SQLDialect dialect;

    private boolean isSkipColumn(DBDAttributeBinding attr) {
        return attr.isPseudoAttribute() || (!includeAutoGenerated && attr.isAutoGenerated());
    }

    @Override
    public void init(IStreamDataExporterSite site) throws DBException
    {
        super.init(site);

        if (site.getProperties().containsKey(PROP_INCLUDE_AUTO_GENERATED)) {
            includeAutoGenerated = CommonUtils.toBoolean(site.getProperties().get(PROP_INCLUDE_AUTO_GENERATED));
        }
        if (site.getProperties().containsKey(PROP_OMIT_SCHEMA)) {
            omitSchema = CommonUtils.toBoolean(site.getProperties().get(PROP_OMIT_SCHEMA));
        }
        try {
            rowsInStatement = Integer.parseInt(String.valueOf(site.getProperties().get(PROP_ROWS_IN_STATEMENT)));
        } catch (NumberFormatException e) {
            rowsInStatement = 10;
        }
        rowDelimiter = GeneralUtils.getDefaultLineSeparator();
        dialect = SQLUtils.getDialectFromObject(site.getSource());
    }

    @Override
    public void dispose()
    {
        super.dispose();
    }

    @Override
    public void exportHeader(DBCSession session) throws DBException, IOException
    {
        columns = getSite().getAttributes();
        DBPNamedObject source = getSite().getSource();
        tableName = DTUtils.getTableName(session.getDataSource(), source, omitSchema);

        rowCount = 0;
    }

    @Override
    public void exportRow(DBCSession session, DBCResultSet resultSet, Object[] row) throws DBException, IOException
    {
        PrintWriter out = getWriter();
        SQLDialect.MultiValueInsertMode insertMode = rowsInStatement == 1 ? SQLDialect.MultiValueInsertMode.NOT_SUPPORTED : getMultiValueInsertMode();
        if (insertMode == SQLDialect.MultiValueInsertMode.NOT_SUPPORTED) {
            rowsInStatement = 1;
        }
        int columnsSize = columns.length;
        boolean firstRow = false;
        if (insertMode == SQLDialect.MultiValueInsertMode.NOT_SUPPORTED || rowCount % rowsInStatement == 0) {
            sqlBuffer.setLength(0);
            if (rowCount > 0) {
                if (insertMode == SQLDialect.MultiValueInsertMode.PLAIN) {
                    sqlBuffer.append(");").append(rowDelimiter);
                } else if (insertMode == SQLDialect.MultiValueInsertMode.GROUP_ROWS) {
                    sqlBuffer.append(";").append(rowDelimiter);
                }
            }
            sqlBuffer.append("INSERT INTO ").append(tableName).append(" (");
            boolean hasColumn = false;
            for (int i = 0; i < columnsSize; i++) {
                DBDAttributeBinding column = columns[i];
                if (isSkipColumn(column)) {
                    continue;
                }
                if (hasColumn) {
                    sqlBuffer.append(',');
                }
                hasColumn = true;
                sqlBuffer.append(DBUtils.getQuotedIdentifier(column));
            }
            sqlBuffer.append(") VALUES ");
            if (insertMode != SQLDialect.MultiValueInsertMode.GROUP_ROWS) {
                sqlBuffer.append("(");
            }
            if (rowsInStatement > 1) {
                sqlBuffer.append(rowDelimiter);
            }
            out.write(sqlBuffer.toString());
            firstRow = true;
        }
        if (insertMode != SQLDialect.MultiValueInsertMode.NOT_SUPPORTED && !firstRow) {
            out.write(",");
        }
        if (insertMode == SQLDialect.MultiValueInsertMode.GROUP_ROWS) {
            out.write("(");
        }
        rowCount++;
        boolean hasValue = false;
        for (int i = 0; i < columnsSize; i++) {
            DBDAttributeBinding column = columns[i];
            if (isSkipColumn(column)) {
                continue;
            }

            if (hasValue) {
                out.write(',');
            }
            hasValue = true;
            Object value = row[i];

            if (DBUtils.isNullValue(value)) {
                // just skip it
                out.write(SQLConstants.NULL_VALUE);
            } else if (row[i] instanceof DBDContent) {
                DBDContent content = (DBDContent)row[i];
                try {
                    if (column.getValueHandler() instanceof DBDContentValueHandler) {
                        ((DBDContentValueHandler) column.getValueHandler()).writeStreamValue(session.getProgressMonitor(), session.getDataSource(), column, content, out);
                    } else {
                        // Content
                        // Inline textual content and handle binaries in some special way
                        DBDContentStorage cs = content.getContents(session.getProgressMonitor());
                        if (cs != null) {
                            if (ContentUtils.isTextContent(content)) {
                                try (Reader contentReader = cs.getContentReader()) {
                                    writeStringValue(contentReader);
                                }
                            } else {
                                getSite().writeBinaryData(cs);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn(e);
                } finally {
                    content.release();
                }
            } else if (value instanceof File) {
                out.write("@");
                out.write(((File)value).getAbsolutePath());
            } else {
                out.write(SQLUtils.convertValueToSQL(session.getDataSource(), column, row[i]));
            }
        }
        if (insertMode != SQLDialect.MultiValueInsertMode.PLAIN) {
            out.write(")");
        }
        if (insertMode == SQLDialect.MultiValueInsertMode.NOT_SUPPORTED) {
            out.write(";");
        }
        out.write(rowDelimiter);
    }

    @Override
    public void exportFooter(DBRProgressMonitor monitor) {
        switch (getMultiValueInsertMode()) {
            case GROUP_ROWS:
                if (rowCount > 0) {
                    getWriter().write(";");
                }
                break;
            case PLAIN:
                if (rowCount > 0) {
                    getWriter().write(");");
                }
                break;
            default:
                break;
        }
    }

    private void writeStringValue(String value)
    {
        PrintWriter out = getWriter();
        out.write(STRING_QUOTE);
        if (dialect != null) {
            out.write(dialect.escapeString(value));
        } else {
            out.write(value);
        }
        out.write(STRING_QUOTE);
    }

    private void writeStringValue(Reader reader) throws IOException
    {
        try {
            PrintWriter out = getWriter();
            out.write(STRING_QUOTE);
            // Copy reader
            char buffer[] = new char[2000];
            for (;;) {
                int count = reader.read(buffer);
                if (count <= 0) {
                    break;
                }
                if (dialect != null) {
                    out.write(dialect.escapeString(String.valueOf(buffer, 0, count)));
                } else {
                    out.write(buffer, 0, count);
                }
            }
            out.write(STRING_QUOTE);
        } finally {
            ContentUtils.close(reader);
        }
    }

    private SQLDialect.MultiValueInsertMode getMultiValueInsertMode() {
        SQLDialect.MultiValueInsertMode insertMode = SQLDialect.MultiValueInsertMode.NOT_SUPPORTED;
        if (dialect != null) {
            insertMode = dialect.getMultiValueInsertMode();
        }
        return insertMode;
    }

}

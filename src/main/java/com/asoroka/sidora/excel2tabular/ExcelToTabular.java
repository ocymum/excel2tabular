
package com.asoroka.sidora.excel2tabular;

import static com.asoroka.sidora.excel2tabular.Utilities.createTempFile;
import static com.google.common.collect.Iterables.all;
import static com.google.common.collect.Ordering.natural;
import static org.apache.poi.ss.usermodel.Cell.CELL_TYPE_BLANK;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.hssf.eventusermodel.FormatTrackingHSSFListener;
import org.apache.poi.hssf.eventusermodel.HSSFEventFactory;
import org.apache.poi.hssf.eventusermodel.HSSFRequest;
import org.apache.poi.hssf.eventusermodel.HSSFUserException;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.slf4j.Logger;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Ordering;
import com.google.common.collect.Range;
import com.google.common.io.ByteSink;
import com.google.common.io.ByteSource;
import com.google.common.io.Files;
import com.google.common.io.Resources;

/**
 * @author ajs6f
 */
public class ExcelToTabular {

    private String delimiter = ",";

    private String quoteChar = "\"";

    final private static Logger log = getLogger(ExcelToTabular.class);

    public List<File> process(final URL inputUrl) {

        final File spreadsheet = createTempFile(this);

        final ByteSource source = Resources.asByteSource(inputUrl);
        final ByteSink sink = Files.asByteSink(spreadsheet);
        try {
            source.copyTo(sink);
        } catch (final IOException e) {
            throw new ExcelParsingException("Could not retrieve input URL: " + inputUrl, e);
        }

        try (final InputStream wbInputStream = new FileInputStream(spreadsheet)) {
            final Workbook wb = WorkbookFactory.create(spreadsheet);

            final int numberOfSheets = wb.getNumberOfSheets();
            // the row-coordinates of the "raw data" in each sheet
            final List<Range<Integer>> dataRows = new ArrayList<>(numberOfSheets);

            for (int i = 0; i < numberOfSheets; i++) {
                log.debug("Examining sheet number {} for data row-range...", i);
                final Sheet sheet = wb.getSheetAt(i);
                final int lastRowIndex = sheet.getLastRowNum();
                // only examine and process a sheet if it has any rows
                final int firstRowNum = sheet.getFirstRowNum();
                if (firstRowNum != lastRowIndex) {
                    log.debug("Found {} rows in sheet {}.", lastRowIndex - firstRowNum, i);
                    final Row maximalRow = compareByRowLength.max(sheet);
                    final int maximalRowIndex = maximalRow.getRowNum();

                    log.trace("Found index of maximally long row at: {} with length: {}", maximalRowIndex, maximalRow
                            .getLastCellNum());

                    // start by assuming that the rest of the sheet is not data
                    int endOfDataRange = maximalRowIndex;
                    boolean noIgnoredRowYet = true;
                    while (noIgnoredRowYet && endOfDataRange++ <= lastRowIndex) {
                        // check whether it's blank and advance the row
                        noIgnoredRowYet = !rowIsIgnored(sheet.getRow(endOfDataRange));
                    }

                    // start by assuming that the sheet has no data before the maximal row
                    int beginningOfDataRange = maximalRowIndex;
                    noIgnoredRowYet = true;
                    while (noIgnoredRowYet && beginningOfDataRange-- >= 0) {
                        noIgnoredRowYet = !rowIsIgnored(sheet.getRow(beginningOfDataRange));
                    }
                    final Range<Integer> dataRange = Range.closed(++beginningOfDataRange, --endOfDataRange);
                    log.trace("Found data range: {}", dataRange);
                    dataRows.add(dataRange);
                } else {
                    log.debug("Found no rows in sheet {}.", i);
                }
            }
            log.trace("Translating sheets with data.");

            final XLS2CSV tabularTransformer = new XLS2CSV()
                    .delimiter(delimiter)
                    .quoteChar(quoteChar).
                    rangesPerSheet(dataRows);
            final AbortableMissingRecordAwareHSSFListener missingRecordListener =
                    new AbortableMissingRecordAwareHSSFListener(tabularTransformer);
            final FormatTrackingHSSFListener ftListener = new FormatTrackingHSSFListener(missingRecordListener);
            tabularTransformer.setFormatListener(ftListener);

            final HSSFRequest request = new HSSFRequest();
            request.addListenerForAllRecords(ftListener);

            request.addListenerForAllRecords(missingRecordListener);

            final HSSFEventFactory factory = new HSSFEventFactory();
            factory.abortableProcessWorkbookEvents(request, new POIFSFileSystem(wbInputStream));
            return tabularTransformer.getOutputs();

        } catch (IOException | InvalidFormatException | HSSFUserException e) {
            throw new ExcelParsingException("Could not parse input spreadsheet: " + spreadsheet, e);
        }
    }

    private static final Function<Row, Short> rowLength = new Function<Row, Short>() {

        @Override
        public Short apply(final Row r) {
            return r.getLastCellNum();
        }
    };

    private static final Ordering<Row> compareByRowLength = natural().onResultOf(rowLength);

    /**
     * Ignore a row if it is null (doesn't exist in the sheet), blank, or contains any part of a merged region.
     * 
     * @param row
     * @return
     */
    private static boolean rowIsIgnored(final Row row) {
        if (row == null) {
            return true;
        }
        final int rowNum = row.getRowNum();
        log.trace("Checking row {} for blankness", rowNum);
        log.trace("Found row with {} cells and {} physical cells.", row.getLastCellNum(), row
                .getPhysicalNumberOfCells());
        if (row.getPhysicalNumberOfCells() == 0) {
            return true;
        }
        final Sheet sheet = row.getSheet();
        final int numMergedRegions = sheet.getNumMergedRegions();
        for (int j = 0; j < numMergedRegions; j++) {
            final CellRangeAddress mergedRegion = sheet.getMergedRegion(j);
            final boolean isInMerged = mergedRegion.getFirstRow() <= rowNum && rowNum <= mergedRegion.getLastRow();
            if (isInMerged) {
                log.debug("Ignoring row {} for belonging to merged region number {}: {}.", rowNum, j,
                        mergedRegion.formatAsString());
                return true;
            }
        }
        final boolean allBlankCells = all(row, blankCell);
        if (allBlankCells) {
            log.trace("Found all blank cells in row number {}.", rowNum);
            return true;
        }
        return false;
    }

    private static final Predicate<Cell> blankCell = new Predicate<Cell>() {

        @Override
        public boolean apply(final Cell cell) {
            return cell.getCellType() == CELL_TYPE_BLANK;
        }
    };

    /**
     * @param delimiter the delimiter to use in output between cells
     */
    public void setDelimiter(final String delimiter) {
        this.delimiter = delimiter;
    }

    /**
     * @param quoteChar the quote string to use in output around strings. May be more than one character.
     */
    public void setQuoteChar(final String quoteChar) {
        this.quoteChar = quoteChar;
    }

}
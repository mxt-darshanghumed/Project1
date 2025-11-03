package com.example.demo.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import com.example.demo.entity.BungalowRate;
import com.example.demo.repository.RateRepository;
import com.example.demo.utils.Utils;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;


/**
 * Service layer responsible for managing Rate entities and handling all business logic
 * related to pricing rules for bungalow stays.
 *
 * <p>This service acts as the core of the application’s rate system. It ensures that:
 * <ul>
 *   <li>BungalowRate are created correctly with proper date validation and normalization</li>
 *   <li>Adjacent and overlapping BungalowRate are intelligently merged or split</li>
 *   <li>Historic (closed) BungalowRate remain queryable using booking date rules</li>
 *   <li>Soft and hard delete operations maintain pricing history integrity</li>
 *   <li>Excel import/export supports bulk handling of rate configurations</li>
 *   <li>Dynamic price calculation is performed based on booking and stay dates</li>
 * </ul>
 *
 * <p>All database operations are executed within a transactional context to ensure
 * consistency and rollback behavior."
 */
@Service
@Transactional
public class RateService{

    /**
     * Repository responsible for CRUD operations on Rate data.
     * Used by this service to query and persist pricing configuration.
     */
    @Autowired
    private RateRepository ratesRepository;
    private Utils util;

    /**
     * Retrieves all rate entries stored in the system.
     *
     * @return list of all {@link BungalowRate} entities in the database
     */
    public List<BungalowRate> getAllRates() {
        return ratesRepository.findAll();
    }

    /**
     * Retrieves a specific rate by its unique identifier.
     *
     * <p>If no rate exists for the provided ID, a {@link RuntimeException} is thrown
     * with a helpful message, ensuring proper error transparency.</p>
     *
     * @param id unique identifier of the rate entity
     * @return matching {@link BungalowRate} record
     */
    public BungalowRate getRateById(Long id) {
        return ratesRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Rate not found with id " + id));
    }

    /**
     * Retrieves all valid rate entries for a specific bungalow,
     * ordered by the start of the stay date period in ascending order.
     *
     * <p>This ensures that pricing rules can be read in chronological order
     * when rendering calendars or performing cost calculations.</p>
     *
     * @param bungalowId identifier representing the bungalow
     * @return list of sorted {@link BungalowRate} linked to the given bungalow
     */
    public List<BungalowRate> getRatesByBungalowId(Long bungalowId) {
        return ratesRepository.findByBungalowIdOrderByStayDateFrom(bungalowId);
    }

    /**
     * Creates a new rate while maintaining temporal integrity with existing rules.
     *
     * <p>This method performs several critical operations:</p>
     * <ul>
     *   <li>Normalizes nightly pricing if more than one night is provided</li>
     *   <li>Checks if a duplicate already exists</li>
     *   <li>Defaults the booking start date to today if not supplied</li>
     *   <li>Splits and closes any overlapping rate segments</li>
     *   <li>Persists the new rate as an active pricing rule</li>
     *   <li>Attempts merging adjacent rules with identical conditions</li>
     * </ul>
     *
     * <p>All created or modified BungalowRate ensure continuous and non-overlapping stay date ranges
     * for active pricing.</p>
     *
     * @param newRate pricing rule to be added
     * @return saved {@link BungalowRate} entity with identifier populated
     */
    public BungalowRate createRate(BungalowRate newRate) {

        // Call utility method to normalize and validate
        util.prepareNewRate(newRate);

        // Split overlapping rates
        splitOverlappingRates(newRate);

        // Insert new rate as active
        newRate.setBookingDateTo(null);
        BungalowRate saved = ratesRepository.save(newRate);

        // Merge adjacent rates if identical
        mergeAdjacentRates(Long.valueOf(newRate.getBungalowId()));

        return saved;
    }
    /**
     * Splits existing overlapping active rate records into separate segments
     * to accommodate a newly inserted rate.
     *
     * <p>When an overlap is detected:</p>
     * <ul>
     *   <li>The old rule is closed (its bookDateTo updated)</li>
     *   <li>A "before" portion is created if the old rule starts earlier</li>
     *   <li>An "after" portion is created if the old rule extends longer</li>
     * </ul>
     *
     * <p>Each new segment inherits value and nights from the original rate.</p>
     *
     * @param newRate the incoming rate that triggers the segmentation
     */
    private void splitOverlappingRates(BungalowRate newRate) {
        LocalDate newFrom = newRate.getStayDateFrom();
        LocalDate newTo = newRate.getStayDateTo();

        List<BungalowRate> overlappingRates =
                ratesRepository.findByBungalowIdAndBookingDateToIsNullAndStayDateToGreaterThanEqualAndStayDateFromLessThanEqual(
                        Long.valueOf(newRate.getBungalowId()), newFrom, newTo);

        for (BungalowRate oldRate : overlappingRates) {
            util.splitRateIfOverlapping(oldRate, newRate);
        }
    }

    /**
     * Merges adjacent active pricing rules into larger continuous ranges
     * when both value and booking condition compatibility allow it.
     *
     * <p>Merging occurs only when:</p>
     * <ul>
     *   <li>Stay date ranges are chronologically continuous</li>
     *   <li>Values match exactly</li>
     *   <li>No conflicting overlapping rate exists with a different value</li>
     * </ul>
     *
     * <p>This ensures the database remains optimized and human-readable
     * without redundant fragmented segments.</p>
     *
     * @param bungalowId the bungalow whose active pricing rules must be combined
     */
    @Transactional
    public void mergeAdjacentRates(Long bungalowId) {
        List<BungalowRate> activeRates = ratesRepository
                .findByBungalowIdAndBookingDateToIsNullOrderByStayDateFrom(bungalowId);

        if (activeRates.size() < 2) return;

        for (int i = 0; i < activeRates.size() - 1; i++) {
            BungalowRate current = activeRates.get(i);
            BungalowRate next = activeRates.get(i + 1);

            util.mergeIfAdjacent(current, next);
        }
    }


    /**
     * Soft deletes a rate by closing its booking availability.
     *
     * <p>This does not remove the rate from the system. Instead, it sets the
     * {@code bookDateTo} field so that new bookings can no longer use this rule.
     * Pricing history remains intact for past or overlapping reservations.</p>
     *
     * @param rateId unique identifier of the rate to close
     * @param dated cutoff date when this rate should no longer be active
     * @throws RuntimeException if the rate does not exist
     */
    public void closeRate(Long rateId, LocalDate dated) {
        BungalowRate rate = ratesRepository.findById(rateId)
                .orElseThrow(() -> new RuntimeException("Rate not found"));

        // closing the rate by setting the bookingDateTo as today
        rate.setBookingDateTo(dated);
        ratesRepository.save(rate);
    }

    /**
     * Hard deletes a rate from the database.
     *
     * <p>This permanently removes the rate record and should be used only when the
     * pricing rule was incorrect or inserted by mistake. If the rule has been used
     * for real booking calculations, prefer {@link #closeRate(Long, LocalDate)}
     * to maintain history integrity.</p>
     *
     * @param rateId unique identifier of the rate to remove
     * @throws RuntimeException if the rate does not exist
     */
    public void deleteRate(Long rateId) {
        if (!ratesRepository.existsById(rateId)) {
            throw new RuntimeException("Rate not found");
        }
        ratesRepository.deleteById(rateId);
    }

    /**
     * Updates an existing rate while preserving historical pricing integrity.
     *
     * <p>The current rate record is first closed to ensure that past bookings
     * remain traceable. A new rate entry is then created using the fields
     * provided in {@code updatedRate}, triggering the same splitting and merging
     * logic used for fresh insert operations.</p>
     *
     * <p>This approach ensures overlapping and adjacent pricing rules remain
     * consistent, recalculated, and correctly versioned for future bookings.</p>
     *
     * @param id identifier of the rate to be replaced
     * @param updatedRate new rate details that will become active going forward
     * @return the newly created {@link BungalowRate} entity representing updated pricing
     * @throws RuntimeException if no rate exists for the provided ID
     */
    public BungalowRate updateRate(Long id, BungalowRate updatedRate) {
        BungalowRate current = ratesRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Rate not found"));

        // Step 1: soft close current rate
        current.setBookingDateTo(LocalDate.now());
        ratesRepository.save(current);

        // Step 2: updatedRate becomes new starting rate
        updatedRate.setRateId(null); // new record
        updatedRate.setBookingDateFrom(LocalDate.now());

        // Step 3: re-run all split and merge magic
        return createRate(updatedRate);
    }

    /**
     * Exports all rate records into an Excel spreadsheet.
     *
     * <p>The generated file contains a single sheet titled "BungalowRate"
     * with columns matching the {@link BungalowRate} entity fields. This
     * provides administrators a convenient snapshot of current and
     * historical pricing data for backup or audit tasks.</p>
     *
     * @return a {@link ByteArrayInputStream} representing the Excel file content
     * @throws IOException if an issue occurs during writing of file data
     */
    public ByteArrayInputStream exportRatesToExcel() throws IOException {
        List<BungalowRate> BungalowRate = ratesRepository.findAll();

        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("BungalowRate");

        Row header = sheet.createRow(0);
        String[] columns = {"ID", "BungalowID", "StayDateFrom", "StayDateTo",
                "Nights", "Value", "BookDateFrom", "BookDateTo"};

        for (int i = 0; i < columns.length; i++) {
            header.createCell(i).setCellValue(columns[i]);
        }

        int rowIdx = 1;
        for (BungalowRate r : BungalowRate) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(r.getRateId());
            row.createCell(1).setCellValue(r.getBungalowId());
            row.createCell(2).setCellValue(r.getStayDateFrom().toString());
            row.createCell(3).setCellValue(r.getStayDateTo().toString());
            row.createCell(4).setCellValue(r.getNights());
            row.createCell(5).setCellValue(r.getValue());
            row.createCell(6).setCellValue(r.getBookingDateFrom().toString());
            row.createCell(7).setCellValue(r.getBookingDateTo() != null ? r.getBookingDateTo().toString() : "");
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        workbook.write(bos);
        workbook.close();
        return new ByteArrayInputStream(bos.toByteArray());
    }

    /**
     * Imports rate data from an uploaded Excel file.
     *
     * <p>Each row is converted into a {@link BungalowRate} entity and inserted using the
     * existing {@link #createRate(BungalowRate)} workflow. This guarantees that rule
     * overlap handling, splitting, and merging are respected automatically during
     * import.</p>
     *
     * <p>The first sheet of the file is processed and the first row is expected
     * to contain column headers. Numeric and string values are read according
     * to their column position.</p>
     *
     * @param file Excel upload that contains rate records
     * @throws IOException if the uploaded file cannot be read
     */
    public void importRatesFromExcel(MultipartFile file) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                BungalowRate rate = util.parseRowToBungalowRate(row);

                // Use createRate to handle splitting and merging
                createRate(rate);
            }
        }
    }

    /**
     * Calculates the total price for a stay at a given bungalow by evaluating the nightly rate
     * for each date between the arrival and departure dates. The applicable rate for every night
     * is determined by both the stay date and the booking date.
     *
     * <p>This method:
     * <ul>
     *     <li>Validates that arrival is strictly before departure.</li>
     *     <li>Fetches all rate records for the given bungalow ordered by stay date.</li>
     *     <li>Iterates date-by-date from arrival (inclusive) to departure (exclusive).</li>
     *     <li>For each night:
     *         <ul>
     *           <li>Finds the first matching rate whose stay and booking date windows are valid.</li>
     *           <li>Calculates nightly cost as {@code rate.value / rate.nights}.</li>
     *           <li>Adds it to the total price.</li>
     *         </ul>
     *     </li>
     *     <li>If at any date no rate fits, it throws an exception.</li>
     * </ul></p>
     *
     * @param bungalowId the ID of the bungalow to fetch applicable BungalowRate from
     * @param arrival the start date of the stay (inclusive)
     * @param departure the end date of the stay (exclusive)
     * @param bookingDate the date when the booking is made to check booking validity
     * @return total calculated price for the entire stay duration
     *
     * @throws IllegalArgumentException if arrival is not before departure
     * @throws RuntimeException if no valid rate is found for any night in the stay period
     */
    public double calculatePrice(Long bungalowId, LocalDate arrival, LocalDate departure, LocalDate bookingDate) {

        if (!arrival.isBefore(departure)) {
            throw new IllegalArgumentException("Arrival date must be before departure date");
        }

        // Fetch all rates for the bungalow
        List<BungalowRate> allRates = ratesRepository.findByBungalowIdOrderByStayDateFrom(bungalowId);

        // Separate closed and active rates without Stream API
        List<BungalowRate> closedRates = new ArrayList<>();
        List<BungalowRate> activeRates = new ArrayList<>();

        for (BungalowRate r : allRates) {
            if (r.getBookingDateTo() != null) {
                closedRates.add(r);
            } else {
                activeRates.add(r);
            }
        }

        LocalDate current = arrival;
        double totalPrice = 0.0;

        while (!current.isEqual(departure)) {
            BungalowRate matchedRate = util.findApplicableRate(current, bookingDate, activeRates, closedRates);

            if (matchedRate == null) {
                throw new RuntimeException("No rate found for date: " + current);
            }

            double perNight = matchedRate.getValue() / matchedRate.getNights();
            totalPrice += perNight;

            current = current.plusDays(1);
        }

        return totalPrice;
    }


}
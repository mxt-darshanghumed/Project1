package com.example.demo.utils;

import com.example.demo.entity.BungalowRate;
import com.example.demo.repository.RateRepository;
import com.example.demo.service.RateValidator;
import org.apache.poi.ss.usermodel.Row;
import java.time.LocalDate;
import java.util.List;

/**
 * Utility class containing helper methods for managing and processing {@link BungalowRate} entities.
 *
 * <p>This class centralizes common operations such as:
 * <ul>
 *     <li>Normalizing rates to one-night pricing</li>
 *     <li>Validating and preparing new rates</li>
 *     <li>Splitting overlapping rates</li>
 *     <li>Merging adjacent rates with identical pricing</li>
 *     <li>Finding applicable rates for a given stay date and booking date</li>
 *     <li>Parsing Excel rows into {@link BungalowRate} instances</li>
 * </ul>
 * </p>
 */
public class Utils {

    private RateRepository ratesRepository;

    /**
     * Normalizes a new BungalowRate and validates it against existing rates for the same bungalow.
     *
     * <p>Steps performed:
     * <ul>
     *     <li>Normalizes multi-night rates to per-night pricing</li>
     *     <li>Sets default booking end date if missing</li>
     *     <li>Validates the new rate against existing rates using {@link RateValidator}</li>
     * </ul>
     * </p>
     *
     * @param newRate The new {@link BungalowRate} to prepare
     */
    public void prepareNewRate(BungalowRate newRate) {
        normalizeRate(newRate);

        if (newRate.getBookingDateFrom() == null)
        {
            newRate.setBookingDateTo(LocalDate.now());
        }

        List<BungalowRate> existing = ratesRepository.findByBungalowIdOrderByStayDateFrom(
                Long.valueOf(newRate.getBungalowId())
        );

        RateValidator.validateNewRate(newRate, existing);
    }

    /**
     * Splits an existing rate if it overlaps with a new rate.
     *
     * <p>This method:
     * <ul>
     *     <li>Closes the old rate by setting its booking end date</li>
     *     <li>Creates "before" and "after" segments if portions of the old rate are outside the new rate's dates</li>
     *     <li>Deletes the old rate if it becomes invalid after splitting</li>
     * </ul>
     * </p>
     *
     * @param oldRate The existing {@link BungalowRate} that may overlap
     * @param newRate The new {@link BungalowRate} being inserted
     */
    public void splitRateIfOverlapping(BungalowRate oldRate, BungalowRate newRate) {
        LocalDate oldFrom = oldRate.getStayDateFrom();
        LocalDate oldTo = oldRate.getStayDateTo();
        LocalDate newFrom = newRate.getStayDateFrom();
        LocalDate newTo = newRate.getStayDateTo();

        // Close old rate
        oldRate.setBookingDateTo(newRate.getBookingDateFrom());
        ratesRepository.save(oldRate);

        // Before segment
        if (oldFrom.isBefore(newFrom)) {
            BungalowRate before = cloneRate(oldRate);
            before.setStayDateFrom(oldFrom);
            before.setStayDateTo(newFrom.minusDays(1));
            before.setBookingDateFrom(newRate.getBookingDateFrom());
            before.setBookingDateTo(null);
            before.setRateId(null);
            ratesRepository.save(before);
        }

        // After segment
        if (oldTo.isAfter(newTo)) {
            BungalowRate after = cloneRate(oldRate);
            after.setStayDateFrom(newTo.plusDays(1));
            after.setStayDateTo(oldTo);
            after.setBookingDateFrom(newRate.getBookingDateFrom());
            after.setBookingDateTo(null);
            after.setRateId(null);
            ratesRepository.save(after);
        }

        // Remove invalid old rate
        if (oldRate.getBookingDateFrom().isAfter(oldRate.getBookingDateTo())) {
            ratesRepository.delete(oldRate);
        }
    }

    /**
     * Merges two adjacent BungalowRates if they have identical value and are continuous.
     *
     * <p>This method:
     * <ul>
     *     <li>Closes the first rate by setting its booking end date</li>
     *     <li>Creates a new merged rate combining both periods</li>
     *     <li>Deletes the second rate after merging</li>
     * </ul>
     * </p>
     *
     * @param current The current rate in sequence
     * @param next    The next rate immediately after current
     */
    public void mergeIfAdjacent(BungalowRate current, BungalowRate next) {
        boolean sameValue = current.getValue().equals(next.getValue());
        boolean continuous = current.getStayDateTo().plusDays(1).equals(next.getStayDateFrom());

        if (sameValue && continuous) {
            // Close current rate
            current.setBookingDateTo(next.getBookingDateFrom());
            ratesRepository.save(current);

            // Create merged rate
            BungalowRate merged = new BungalowRate();
            merged.setBungalowId(current.getBungalowId());
            merged.setStayDateFrom(current.getStayDateFrom());
            merged.setStayDateTo(next.getStayDateTo());
            merged.setValue(current.getValue());
            merged.setNights(1);
            merged.setBookingDateFrom(next.getBookingDateFrom());
            merged.setBookingDateTo(null);

            ratesRepository.save(merged);

            // Remove next rate
            ratesRepository.delete(next);
        }
    }

    /**
     * Clones key pricing fields from a source rate into a new instance.
     *
     * <p>ID and date ranges are intentionally omitted to allow safe creation of new split segments.</p>
     *
     * @param source The original rate to clone
     * @return A new {@link BungalowRate} with the same pricing and bungalow ID
     */
    private BungalowRate cloneRate(BungalowRate source) {
        BungalowRate r = new BungalowRate();
        r.setBungalowId(source.getBungalowId());
        r.setValue(source.getValue());
        r.setNights(source.getNights());
        return r;
    }

    /**
     * Normalizes multi-night rates to one-night pricing.
     *
     * <p>Divides the total value evenly by the number of nights and sets nights to 1.</p>
     *
     * @param rate The rate to normalize
     */
    private void normalizeRate(BungalowRate rate) {
        if (rate.getNights() > 1) {
            double perNightValue = rate.getValue() / rate.getNights();
            rate.setNights(1);
            rate.setValue(perNightValue);
        }
    }

    /**
     * Finds the applicable rate for a given stay and booking date.
     *
     * <p>Checks closed (historical) rates first, then active rates if no match is found.</p>
     *
     * @param stayDate    The date of stay
     * @param bookingDate The booking date
     * @param activeRates List of active rates
     * @param closedRates List of closed (historical) rates
     * @return The matching {@link BungalowRate} or null if none found
     */
    public BungalowRate findApplicableRate(LocalDate stayDate, LocalDate bookingDate,
                                           List<BungalowRate> activeRates, List<BungalowRate> closedRates) {

        // Check closed (historical) rates first
        for (BungalowRate r : closedRates) {
            if (!stayDate.isBefore(r.getStayDateFrom())
                    && !stayDate.isAfter(r.getStayDateTo())
                    && !bookingDate.isBefore(r.getBookingDateFrom())
                    && !bookingDate.isAfter(r.getBookingDateTo())) {
                return r;
            }
        }

        // Fallback to active rates
        for (BungalowRate r : activeRates) {
            if (!stayDate.isBefore(r.getStayDateFrom())
                    && !stayDate.isAfter(r.getStayDateTo())
                    && !bookingDate.isBefore(r.getBookingDateFrom())) {
                return r;
            }
        }

        return null;
    }

    /**
     * Converts an Excel {@link Row} into a {@link BungalowRate} instance.
     *
     * <p>Handles optional booking end date.</p>
     *
     * @param row The Excel row to parse
     * @return A populated {@link BungalowRate} object
     */
    public BungalowRate parseRowToBungalowRate(Row row) {
        BungalowRate rate = new BungalowRate();

        rate.setBungalowId((int) row.getCell(1).getNumericCellValue());
        rate.setStayDateFrom(LocalDate.parse(row.getCell(2).getStringCellValue()));
        rate.setStayDateTo(LocalDate.parse(row.getCell(3).getStringCellValue()));
        rate.setNights((int) row.getCell(4).getNumericCellValue());
        rate.setValue(row.getCell(5).getNumericCellValue());
        rate.setBookingDateFrom(LocalDate.parse(row.getCell(6).getStringCellValue()));

        if (row.getCell(7) != null) {
            String bookToValue = row.getCell(7).getStringCellValue();
            rate.setBookingDateTo(bookToValue.isBlank() ? null : LocalDate.parse(bookToValue));
        }

        return rate;
    }
}

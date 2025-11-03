package com.example.demo.service;

import com.example.demo.entity.BungalowRate;
import com.example.demo.repository.RateRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * Service class for managing bungalow rates including CRUD operations,
 * overlapping rate handling, merging adjacent rates, Excel import/export,
 * and stay price calculation.
 */
@Service
@Transactional
public class RateService {

    @Autowired
    private RateRepository rateRepository;

    // ==========================================================
    // Basic CRUD Operations
    // ==========================================================

    /**
     * Fetches all bungalow rates from the database.
     *
     * @return List of all BungalowRate entities.
     */
    public List<BungalowRate> fetchAllRates() {
        return rateRepository.findAll();
    }

    /**
     * Fetches a specific bungalow rate by its ID.
     *
     * @param id the ID of the rate to fetch.
     * @return BungalowRate with the specified ID.
     * @throws RuntimeException if the rate is not found.
     */
    public BungalowRate fetchRateById(Long id) {
        return rateRepository.findById(Math.toIntExact(id))
                .orElseThrow(() -> new RuntimeException("Rate not found with id " + id));
    }

    /**
     * Fetches all active rates for a specific bungalow.
     *
     * @param bungalowId the ID of the bungalow.
     * @return List of active BungalowRate entities for the bungalow.
     */
    public List<BungalowRate> fetchActiveRatesByBungalow(Long bungalowId) {
        return rateRepository.getActiveRates(Math.toIntExact(bungalowId));
    }

    /**
     * Deletes a rate by its ID.
     *
     * @param rateId the ID of the rate to delete.
     * @throws RuntimeException if the rate does not exist.
     */
    public void removeRate(Long rateId) {
        if (!rateRepository.existsById(Math.toIntExact(rateId))) {
            throw new RuntimeException("Rate not found");
        }
        rateRepository.deleteById(Math.toIntExact(rateId));
    }

    /**
     * Closes a rate by setting its booking end date.
     *
     * @param rateId  the ID of the rate to close.
     * @param endDate the closing date to set.
     * @throws RuntimeException if the rate is not found.
     */
    public void closeRate(Long rateId, LocalDate endDate) {
        BungalowRate rate = rateRepository.findById(Math.toIntExact(rateId))
                .orElseThrow(() -> new RuntimeException("Rate not found"));
        rate.setBookingDateTo(endDate);
        rateRepository.save(rate);
    }

    // ==========================================================
    // Rate Creation and Update
    // ==========================================================

    /**
     * Adds a new rate, normalizing it and handling any overlaps with existing rates.
     * Also merges adjacent rates after addition.
     *
     * @param newRate the rate to add.
     * @return the saved BungalowRate entity.
     */
    public BungalowRate addRate(BungalowRate newRate) {
        normalizeRate(newRate);

        if (newRate.getBookingDateFrom() == null) {
            newRate.setBookingDateFrom(LocalDate.now());
        }

        splitOverlappingRates(newRate);

        newRate.setBookingDateTo(null);
        BungalowRate saved = rateRepository.save(newRate);

        mergeAdjacentRates(newRate.getBungalowId());

        return saved;
    }

    /**
     * Updates an existing rate by closing the current rate and adding a new rate.
     *
     * @param id          the ID of the rate to update.
     * @param updatedRate the new rate details.
     * @return the newly added BungalowRate entity.
     * @throws RuntimeException if the original rate is not found.
     */
    public BungalowRate updateRate(Long id, BungalowRate updatedRate) {
        BungalowRate current = rateRepository.findById(Math.toIntExact(id))
                .orElseThrow(() -> new RuntimeException("Rate not found"));

        current.setBookingDateTo(LocalDate.now());
        rateRepository.save(current);

        updatedRate.setRateId(null);
        updatedRate.setBookingDateFrom(LocalDate.now());

        return addRate(updatedRate);
    }

    // ==========================================================
    // Split and Merge Operations
    // ==========================================================

    /**
     * Splits existing overlapping rates to accommodate a new rate.
     *
     * @param newRate the new rate that may overlap with existing ones.
     */
    private void splitOverlappingRates(BungalowRate newRate) {
        LocalDate newFrom = newRate.getStayDateFrom();
        LocalDate newTo = newRate.getStayDateTo();

        List<BungalowRate> overlappingRates =
                rateRepository.getOverlappingRates(newRate.getBungalowId(), newFrom, newTo);

        for (BungalowRate oldRate : overlappingRates) {
            LocalDate oldFrom = oldRate.getStayDateFrom();
            LocalDate oldTo = oldRate.getStayDateTo();

            oldRate.setBookingDateTo(newRate.getBookingDateFrom());
            rateRepository.save(oldRate);

            if (oldFrom.isBefore(newFrom)) {
                BungalowRate beforeSegment = cloneRate(oldRate);
                beforeSegment.setStayDateFrom(oldFrom);
                beforeSegment.setStayDateTo(newFrom.minusDays(1));
                beforeSegment.setBookingDateFrom(newRate.getBookingDateFrom());
                beforeSegment.setBookingDateTo(null);
                beforeSegment.setRateId(null);
                rateRepository.save(beforeSegment);
            }

            if (oldTo.isAfter(newTo)) {
                BungalowRate afterSegment = cloneRate(oldRate);
                afterSegment.setStayDateFrom(newTo.plusDays(1));
                afterSegment.setStayDateTo(oldTo);
                afterSegment.setBookingDateFrom(newRate.getBookingDateFrom());
                afterSegment.setBookingDateTo(null);
                afterSegment.setRateId(null);
                rateRepository.save(afterSegment);
            }

            if (oldRate.getBookingDateFrom().isAfter(oldRate.getBookingDateTo())) {
                rateRepository.delete(oldRate);
            }
        }
    }

    /**
     * Merges adjacent rates for a bungalow if they have the same value
     * and consecutive stay dates.
     *
     * @param bungalowId the ID of the bungalow.
     */
    @Transactional
    public void mergeAdjacentRates(int bungalowId) {
        List<BungalowRate> activeRates = rateRepository.getActiveRates(bungalowId);

        if (activeRates.size() < 2) return;

        for (int i = 0; i < activeRates.size() - 1; i++) {
            BungalowRate current = activeRates.get(i);
            BungalowRate next = activeRates.get(i + 1);

            boolean sameValue = current.getValue() == next.getValue();
            boolean continuous = current.getStayDateTo().plusDays(1).equals(next.getStayDateFrom());

            if (sameValue && continuous) {
                current.setBookingDateTo(next.getBookingDateFrom());
                rateRepository.save(current);

                BungalowRate merged = new BungalowRate();
                merged.setBungalowId(current.getBungalowId());
                merged.setStayDateFrom(current.getStayDateFrom());
                merged.setStayDateTo(next.getStayDateTo());
                merged.setValue(current.getValue());
                merged.setNights(1);
                merged.setBookingDateFrom(next.getBookingDateFrom());
                merged.setBookingDateTo(null);

                rateRepository.save(merged);
                rateRepository.delete(next);
            }
        }
    }

    // ==========================================================
    // Utility Methods
    // ==========================================================

    /**
     * Normalizes the rate to a per-night value if the number of nights > 1.
     *
     * @param rate the rate to normalize.
     */
    private void normalizeRate(BungalowRate rate) {
        if (rate.getNights() != null && rate.getNights() > 1) {
            double perNightValue = rate.getValue() / rate.getNights();
            rate.setNights(1);
            rate.setValue(perNightValue);
        }
    }


    /**
     * Creates a shallow clone of a BungalowRate.
     *
     * @param source the source rate to clone.
     * @return a new BungalowRate instance with the same bungalowId, value, and nights.
     */
    private BungalowRate cloneRate(BungalowRate source) {
        BungalowRate clone = new BungalowRate();
        clone.setBungalowId(source.getBungalowId());
        clone.setValue(source.getValue());
        clone.setNights(source.getNights());
        return clone;
    }

    // ==========================================================
    // Excel Import / Export
    // ==========================================================

    /**
     * Exports all bungalow rates to an Excel file.
     *
     * @return ByteArrayInputStream containing the Excel data.
     * @throws IOException if an I/O error occurs.
     */
    public ByteArrayInputStream exportRatesToExcel() throws IOException {
        List<BungalowRate> rates = rateRepository.findAll();

        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("BungalowRate");

        Row header = sheet.createRow(0);
        String[] columns = {
                "ID", "BungalowID", "StayDateFrom", "StayDateTo",
                "Nights", "Value", "BookDateFrom", "BookDateTo"
        };
        for (int i = 0; i < columns.length; i++) {
            header.createCell(i).setCellValue(columns[i]);
        }

        int rowIdx = 1;
        for (BungalowRate rate : rates) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(rate.getRateId());
            row.createCell(1).setCellValue(rate.getBungalowId());
            row.createCell(2).setCellValue(rate.getStayDateFrom().toString());
            row.createCell(3).setCellValue(rate.getStayDateTo().toString());
            row.createCell(4).setCellValue(rate.getNights());
            row.createCell(5).setCellValue(rate.getValue());
            row.createCell(6).setCellValue(rate.getBookingDateFrom().toString());
            row.createCell(7).setCellValue(
                    rate.getBookingDateTo() != null ? rate.getBookingDateTo().toString() : ""
            );
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        workbook.write(bos);
        workbook.close();
        return new ByteArrayInputStream(bos.toByteArray());
    }

    /**
     * Imports bungalow rates from an Excel file.
     *
     * @param file the uploaded Excel file containing rates.
     * @throws IOException if an I/O error occurs during file processing.
     */
    public void importRatesFromExcel(MultipartFile file) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                BungalowRate rate = new BungalowRate();
                rate.setBungalowId((int) row.getCell(1).getNumericCellValue());
                rate.setStayDateFrom(LocalDate.parse(row.getCell(2).getStringCellValue()));
                rate.setStayDateTo(LocalDate.parse(row.getCell(3).getStringCellValue()));
                rate.setNights((int) row.getCell(4).getNumericCellValue());
                rate.setValue(row.getCell(5).getNumericCellValue());
                rate.setBookingDateFrom(LocalDate.parse(row.getCell(6).getStringCellValue()));

                if (row.getCell(7) != null) {
                    String bookToValue = row.getCell(7).getStringCellValue();
                    rate.setBookingDateTo(
                            bookToValue.isBlank() ? null : LocalDate.parse(bookToValue)
                    );
                }

                addRate(rate);
            }
        }
    }

    // ==========================================================
    // Price Calculation
    // ==========================================================

    /**
     * Calculates the total price for a stay based on bungalow rates.
     *
     * @param bungalowId  the ID of the bungalow.
     * @param arrival     the arrival date of the stay.
     * @param departure   the departure date of the stay.
     * @param bookingDate the date on which the booking is made.
     * @return total price for the stay.
     * @throws IllegalArgumentException if arrival is not before departure.
     * @throws RuntimeException         if no rate is found for a date in the stay.
     */
    public double calculateStayPrice(Long bungalowId,
                                     LocalDate arrival,
                                     LocalDate departure,
                                     LocalDate bookingDate) {

        if (!arrival.isBefore(departure)) {
            throw new IllegalArgumentException("Arrival date must be before departure date");
        }

        List<BungalowRate> allRates = rateRepository.findByBungalowIdOrderByStayDateFrom(Math.toIntExact(bungalowId));

        List<BungalowRate> closedRates = allRates.stream()
                .filter(r -> r.getBookingDateTo() != null)
                .toList();

        List<BungalowRate> activeRates = allRates.stream()
                .filter(r -> r.getBookingDateTo() == null)
                .toList();

        LocalDate current = arrival;
        double totalPrice = 0.0;

        while (!current.isEqual(departure)) {
            BungalowRate matchedRate = null;

            for (BungalowRate r : closedRates) {
                if (!current.isBefore(r.getStayDateFrom())
                        && !current.isAfter(r.getStayDateTo())
                        && !bookingDate.isBefore(r.getBookingDateFrom())
                        && !bookingDate.isAfter(r.getBookingDateTo())) {
                    matchedRate = r;
                    break;
                }
            }

            if (matchedRate == null) {
                for (BungalowRate r : activeRates) {
                    if (!current.isBefore(r.getStayDateFrom())
                            && !current.isAfter(r.getStayDateTo())
                            && !bookingDate.isBefore(r.getBookingDateFrom())) {
                        matchedRate = r;
                        break;
                    }
                }
            }

            if (matchedRate == null) {
                throw new RuntimeException("No rate found for date: " + current);
            }

            totalPrice += matchedRate.getValue() / matchedRate.getNights();
            current = current.plusDays(1);
        }

        return totalPrice;
    }
}

package com.example.demo.controller;

import com.example.demo.entity.BungalowRate;
import com.example.demo.service.RateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * REST Controller for managing {@link BungalowRate} entities.
 * <p>
 * This controller provides complete CRUD functionality and additional operations
 * like Excel import/export, rate merging, splitting, and price calculation.
 * <p>
 * All endpoints are prefixed with <b>/api/BungalowRate</b>.
 */
@RestController
@RequestMapping("/api")
public class RateController {

    @Autowired
    private RateService rateService;

    /**
     * Creates a new {@link BungalowRate} record.
     * <p>
     * This operation automatically checks for adjacent or overlapping date ranges
     * and merges or splits records as needed to maintain continuous and consistent
     * rate periods.
     *
     * @param newRate the {@link BungalowRate} object containing rate details to be created
     * @return the newly created {@link BungalowRate} record
     */
    @PostMapping
    public ResponseEntity<BungalowRate> createRate(@RequestBody BungalowRate newRate) {
        BungalowRate savedRate = rateService.createRate(newRate);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedRate);
    }

    /**
     * Retrieves all {@link BungalowRate} records from the system.
     *
     * @return list of all existing {@link BungalowRate} entities
     */
    @GetMapping
    public ResponseEntity<List<BungalowRate>> getAllRates() {
        return ResponseEntity.ok(rateService.getAllRates());
    }

    /**
     * Retrieves a single {@link BungalowRate} record based on its ID.
     *
     * @param id unique identifier of the rate record
     * @return the corresponding {@link BungalowRate} if found
     */
    @GetMapping("/{id}")
    public ResponseEntity<BungalowRate> getRateById(@PathVariable Long id) {
        BungalowRate rate = rateService.getRateById(id);
        return ResponseEntity.ok(rate);
    }

    /**
     * Retrieves all {@link BungalowRate} records for a specific bungalow.
     *
     * @param bungalowId unique identifier of the bungalow
     * @return list of rates associated with the specified bungalow
     */
    @GetMapping("/bungalow/{bungalowId}")
    public ResponseEntity<List<BungalowRate>> getRatesByBungalowId(@PathVariable Long bungalowId) {
        return ResponseEntity.ok(rateService.getRatesByBungalowId(bungalowId));
    }

    /**
     * Updates an existing {@link BungalowRate} record.
     * <p>
     * The update operation first closes the old rate (if necessary),
     * then creates a new version with the updated details. After updating,
     * the system checks whether merging or splitting of rates is required.
     *
     * @param id          unique identifier of the rate to update
     * @param updatedRate updated rate information
     * @return the updated {@link BungalowRate} entity
     */
    @PutMapping("/{id}")
    public ResponseEntity<BungalowRate> updateRate(@PathVariable Long id, @RequestBody BungalowRate updatedRate) {
        BungalowRate saved = rateService.updateRate(id, updatedRate);
        return ResponseEntity.ok(saved);
    }

    /**
     * Performs a soft delete by marking the {@link BungalowRate} as closed.
     * <p>
     * This sets the {@code bookingDateTo} or {@code closedDate} of the record,
     * effectively ending its active period without deleting it from the database.
     *
     * @param id   unique identifier of the rate to close
     * @param date optional date string to specify closure date (defaults to current date)
     * @return success message indicating the closure
     */
    @PatchMapping("/close/{id}")
    public ResponseEntity<String> closeRate(
            @PathVariable Long id,
            @RequestParam(required = false) String date) {
        LocalDate closeDate = (date != null) ? LocalDate.parse(date) : LocalDate.now();
        rateService.closeRate(id, closeDate);
        return ResponseEntity.ok("Rate with ID " + id + " closed on " + closeDate);
    }

    /**
     * Permanently deletes a {@link BungalowRate} record from the database.
     * <p>
     * Use this operation cautiously, as it removes the record entirely.
     *
     * @param id unique identifier of the rate to delete
     * @return confirmation message upon successful deletion
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteRate(@PathVariable Long id) {
        rateService.deleteRate(id);
        return ResponseEntity.ok("Rate with ID " + id + " deleted permanently.");
    }

    /**
     * Imports rate data from an Excel file.
     * <p>
     * The file should contain rate details in the correct format.
     * During import, the system automatically handles merging and splitting
     * of date ranges to maintain consistent rate intervals.
     *
     * @param file Excel file containing {@link BungalowRate} data
     * @return success or failure message
     */
    @PostMapping("/import")
    public ResponseEntity<String> importRatesFromExcel(@RequestParam("file") MultipartFile file) {
        rateService.importRatesFromExcel(file);
        return ResponseEntity.ok("BungalowRate imported successfully with merge/split handling.");
    }

    /**
     * Exports all existing {@link BungalowRate} records into an Excel file.
     * <p>
     * The generated file includes all current rates and is returned
     * as a downloadable attachment in the HTTP response.
     *
     * @return the generated Excel file as a byte stream
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportRatesToExcel() {
        ByteArrayInputStream in = rateService.exportRatesToExcel();
        byte[] bytes = in.readAllBytes();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=BungalowRate.xlsx");
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

        return ResponseEntity.ok()
                .headers(headers)
                .body(bytes);
    }

    /**
     * Calculates the total price for a guest’s stay in a specific bungalow.
     * <p>
     * The calculation uses applicable rates based on the provided booking date
     * and stay period. If no matching rate exists for the date range, an appropriate
     * message or exception is returned.
     *
     * <pre>
     * Example usage:
     * GET /api/BungalowRate/calculate?bungalowId=1&arrival=2025-05-01&departure=2025-05-05&bookingDate=2025-03-01
     * </pre>
     *
     * @param bungalowId the ID of the bungalow
     * @param arrival    check-in date (format: yyyy-MM-dd)
     * @param departure  check-out date (format: yyyy-MM-dd)
     * @param bookingDate booking date used to determine applicable rate
     * @return total calculated price for the stay
     */
    @GetMapping("/calculate")
    public ResponseEntity<Double> calculatePrice(
            @RequestParam Long bungalowId,
            @RequestParam String arrival,
            @RequestParam String departure,
            @RequestParam String bookingDate) {

        double total = rateService.calculatePrice(
                bungalowId,
                LocalDate.parse(arrival),
                LocalDate.parse(departure),
                LocalDate.parse(bookingDate)
        );

        return ResponseEntity.ok(total);
    }

//    /**
//     * Manually merges adjacent or overlapping {@link BungalowRate} records
//     * for a given bungalow ID. This is useful for maintenance or data cleanup.
//     *
//     * @param bungalowId unique identifier of the bungalow
//     * @return success message upon completion
//     */
//    @PostMapping("/merge/{bungalowId}")
//    public ResponseEntity<String> mergeAdjacentRates(@PathVariable Long bungalowId) {
//        rateService.mergeAdjacentRates(bungalowId);
//        return ResponseEntity.ok("Merged adjacent BungalowRate for bungalow ID " + bungalowId);
//    }
}

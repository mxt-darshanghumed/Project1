package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

/**
 * Entity class representing the rate configuration for a bungalow.
 * <p>
 * Each {@code BungalowRate} record defines a price and stay period
 * for a specific bungalow within a defined booking window.
 * The entity also supports soft-closing of rates using {@code bookingDateTo}.
 * </p>
 *
 * <p><b>Key Behaviors:</b></p>
 * <ul>
 *   <li>When {@code bookingDateTo} is {@code NULL}, the rate is considered <b>active</b>.</li>
 *   <li>Rates may be merged or split depending on overlapping or adjacent stay periods.</li>
 *   <li>Used by services handling rate calculation, import/export, and CRUD operations.</li>
 * </ul>
 *
 * <p><b>Database Table:</b> bungalow_rate</p>
 */
@Entity
@Getter
@Setter
@Table(name = "bungalow_rate")
public class BungalowRate {

    /**
     * Primary key — unique identifier for each rate record.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rate_id", nullable = false, updatable = false)
    private Integer rateId;

    /**
     * Foreign key — identifies the bungalow this rate applies to.
     */
    @Column(name = "bungalow_id", nullable = false)
    private Integer bungalowId;

    /**
     * Start date from which the rate becomes bookable.
     * <p>
     * Example: if booking_date_from = 2025-01-01, guests can start booking from that date.
     * </p>
     */
    @Column(name = "booking_date_from", nullable = false)
    private LocalDate bookingDateFrom;

    /**
     * End date until which the rate is valid for booking.
     * <p>
     * If {@code NULL}, the rate is considered active (not closed).
     * When a rate is updated or replaced, this field is set to mark its closure.
     * </p>
     */
    @Column(name = "booking_date_to", nullable = true)
    private LocalDate bookingDateTo;

    /**
     * Number of nights the rate covers in one booking cycle.
     */
    @Column(name = "nights", nullable = false)
    private Integer nights;

    /**
     * Start date of the stay period that this rate applies to.
     * <p>
     * Example: A guest staying from 2025-02-01 would fall under a rate
     * where {@code stayDateFrom ≤ 2025-02-01 ≤ stayDateTo}.
     * </p>
     */
    @Column(name = "stay_date_from", nullable = false)
    private LocalDate stayDateFrom;

    /**
     * End date of the stay period that this rate applies to.
     */
    @Column(name = "stay_date_to", nullable = false)
    private LocalDate stayDateTo;

    /**
     * Price or value of the rate for the defined stay period.
     * Represented as a double precision value (e.g., 1200.00).
     */
    @Column(name = "value", nullable = false)
    private Double value;

    /**
     * Default no-args constructor required by JPA.
     */
    public BungalowRate() {}

    /**
     * Convenience constructor for creating rate instances programmatically.
     *
     * @param bungalowId       bungalow identifier
     * @param bookingDateFrom  start date when this rate becomes bookable
     * @param bookingDateTo    end date when this rate stops being bookable (nullable)
     * @param nights           number of nights for which this rate applies
     * @param stayDateFrom     start date of the stay period
     * @param stayDateTo       end date of the stay period
     * @param value            rate value (price)
     */
    public BungalowRate(Integer bungalowId,
                        LocalDate bookingDateFrom,
                        LocalDate bookingDateTo,
                        Integer nights,
                        LocalDate stayDateFrom,
                        LocalDate stayDateTo,
                        Double value) {
        this.bungalowId = bungalowId;
        this.bookingDateFrom = bookingDateFrom;
        this.bookingDateTo = bookingDateTo;
        this.nights = nights;
        this.stayDateFrom = stayDateFrom;
        this.stayDateTo = stayDateTo;
        this.value = value;
    }
}

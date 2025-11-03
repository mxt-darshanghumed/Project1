package com.example.demo.repository;

import com.example.demo.entity.BungalowRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository interface for performing database operations on {@link BungalowRate} entities.
 * <p>
 * Provides methods to fetch, merge, and split bungalow rate records
 * based on booking dates, stay dates, and activity status.
 * </p>
 *
 * <p><b>Core Responsibilities:</b></p>
 * <ul>
 *   <li>Retrieve active (non-closed) bungalow rates.</li>
 *   <li>Detect overlapping and adjacent stay periods for merging/splitting logic.</li>
 *   <li>Support CRUD operations through {@link JpaRepository}.</li>
 * </ul>
 */
@Repository
public interface RateRepository extends JpaRepository<BungalowRate, Integer> {

    /**
     * Fetches all active rates for a given bungalow that match the specified
     * nights count and booking start date.
     * <p>
     * Used primarily during <b>merging</b> operations to find existing active records
     * that could be combined with a new or updated rate.
     * </p>
     *
     * @param bungalowId      bungalow identifier
     * @param nights          number of nights the rate applies to
     * @param bookingDateFrom start date of booking validity
     * @return list of matching active rates
     */
    @Query("SELECT r FROM BungalowRate r " +
            "WHERE r.bungalowId = :bungalowId " +
            "AND r.nights = :nights " +
            "AND r.bookingDateTo IS NULL " +
            "AND r.bookingDateFrom = :bookingDateFrom")
    List<BungalowRate> findActiveRates(
            @Param("bungalowId") Integer bungalowId,
            @Param("nights") Integer nights,
            @Param("bookingDateFrom") LocalDate bookingDateFrom
    );

    /**
     * Finds all active (non-closed) rates for a bungalow with a specific
     * nights configuration.
     * <p>
     * Used to detect adjacency between existing and new records during
     * <b>merge or split</b> decisions.
     * </p>
     *
     * @param bungalowId bungalow identifier
     * @param nights     number of nights
     * @return list of active rates with the same nights count
     */
    List<BungalowRate> findByBungalowIdAndNightsAndBookingDateToIsNull(
            Integer bungalowId,
            Integer nights
    );

    /**
     * Finds all active rates that overlap with a given stay date range.
     * <p>
     * Used during <b>splitting</b> operations to identify records that need
     * to be adjusted or divided when new rates are inserted.
     * </p>
     *
     * @param bungalowId bungalow identifier
     * @param nights     number of nights
     * @param stayFrom   start of the new stay period
     * @param stayTo     end of the new stay period
     * @return list of overlapping active rates
     */
    @Query("SELECT r FROM BungalowRate r " +
            "WHERE r.bungalowId = :bungalowId " +
            "AND r.nights = :nights " +
            "AND r.bookingDateTo IS NULL " +
            "AND (" +
            "   (r.stayDateFrom BETWEEN :stayFrom AND :stayTo) OR " +
            "   (r.stayDateTo BETWEEN :stayFrom AND :stayTo) OR " +
            "   (:stayFrom BETWEEN r.stayDateFrom AND r.stayDateTo) OR " +
            "   (:stayTo BETWEEN r.stayDateFrom AND r.stayDateTo)" +
            ")")
    List<BungalowRate> findOverlappingRates(
            @Param("bungalowId") Integer bungalowId,
            @Param("nights") Integer nights,
            @Param("stayFrom") LocalDate stayFrom,
            @Param("stayTo") LocalDate stayTo
    );

    /**
     * Retrieves all currently active (non-closed) bungalow rates in the system.
     *
     * @return list of active rates
     */
    @Query("SELECT r FROM BungalowRate r WHERE r.bookingDateTo IS NULL")
    List<BungalowRate> findAllActiveRates();

    /**
     * Finds all rate records for a given bungalow,
     * ordered by the stay start date.
     *
     * @param bungalowId bungalow identifier
     * @return ordered list of rates
     */
    List<BungalowRate> findByBungalowIdOrderByStayDateFrom(Integer bungalowId);

    /**
     * Finds all rate records (active or closed) for a given bungalow.
     *
     * @param bungalowId bungalow identifier
     * @return list of all rates
     */
    List<BungalowRate> findByBungalowId(Integer bungalowId);

    /**
     * Retrieves all active (non-closed) rates for a bungalow,
     * ordered by the start of the stay period.
     *
     * @param bungalowId bungalow identifier
     * @return list of active rates ordered by stay start date
     */
    @Query("SELECT r FROM BungalowRate r " +
            "WHERE r.bungalowId = :bungalowId " +
            "AND r.bookingDateTo IS NULL " +
            "ORDER BY r.stayDateFrom ASC")
    List<BungalowRate> getActiveRates(@Param("bungalowId") Integer bungalowId);

    /**
     * Retrieves all active overlapping rates for a bungalow
     * within the specified stay period range.
     * <p>
     * Used during validation and conflict detection when creating
     * or updating rate records.
     * </p>
     *
     * @param bungalowId bungalow identifier
     * @param newFrom    start date of new stay period
     * @param newTo      end date of new stay period
     * @return list of overlapping active rates
     */
    @Query("SELECT r FROM BungalowRate r " +
            "WHERE r.bungalowId = :bungalowId " +
            "AND r.bookingDateTo IS NULL " +
            "AND r.stayDateTo >= :newFrom " +
            "AND r.stayDateFrom <= :newTo")
    List<BungalowRate> getOverlappingRates(@Param("bungalowId") Integer bungalowId,
                                           @Param("newFrom") LocalDate newFrom,
                                           @Param("newTo") LocalDate newTo);
}

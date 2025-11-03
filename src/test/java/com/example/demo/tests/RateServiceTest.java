package com.example.demo.tests;

import com.example.demo.entity.BungalowRate;
import com.example.demo.repository.RateRepository;
import com.example.demo.service.RateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateServiceTest {

    @InjectMocks
    private RateService rateService;

    @Mock
    private RateRepository rateRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    // ===============================
    // Test fetchAllRates
    // ===============================
    @Test
    void fetchAllRates_shouldReturnAllRates() {
        BungalowRate rate = new BungalowRate();
        rate.setRateId(1);
        when(rateRepository.findAll()).thenReturn(Collections.singletonList(rate));

        List<BungalowRate> rates = rateService.fetchAllRates();

        assertEquals(1, rates.size());
        verify(rateRepository, times(1)).findAll();
    }

    // ===============================
    // Test fetchRateById
    // ===============================
    @Test
    void fetchRateById_existingRate_shouldReturnRate() {
        BungalowRate rate = new BungalowRate();
        rate.setRateId(1);
        when(rateRepository.findById(1)).thenReturn(Optional.of(rate));

        BungalowRate result = rateService.fetchRateById(1L);
        assertNotNull(result);
        assertEquals(1, result.getRateId());
    }

    @Test
    void fetchRateById_nonExistingRate_shouldThrowException() {
        when(rateRepository.findById(1)).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> rateService.fetchRateById(1L));
    }

    // ===============================
    // Test addRate
    // ===============================
    @Test
    void addRate_shouldNormalizeAndSave() {
        BungalowRate rate = new BungalowRate();
        rate.setBungalowId(1);
        rate.setNights(2);
        rate.setValue(200.0);
        rate.setStayDateFrom(LocalDate.now());
        rate.setStayDateTo(LocalDate.now().plusDays(1));

        when(rateRepository.getOverlappingRates(anyInt(), any(), any())).thenReturn(Collections.emptyList());
        when(rateRepository.save(any(BungalowRate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BungalowRate saved = rateService.addRate(rate);

        assertEquals(1, saved.getNights());
        assertEquals(100.0, saved.getValue());
        verify(rateRepository, atLeastOnce()).save(any(BungalowRate.class));
    }

    // ===============================
    // Test updateRate
    // ===============================
    @Test
    void updateRate_existingRate_shouldCloseAndAddNewRate() {
        BungalowRate current = new BungalowRate();
        current.setRateId(1);
        when(rateRepository.findById(1)).thenReturn(Optional.of(current));
        when(rateRepository.save(any(BungalowRate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BungalowRate updated = new BungalowRate();
        updated.setBungalowId(1);
        updated.setValue(150.0);
        updated.setStayDateFrom(LocalDate.now());
        updated.setStayDateTo(LocalDate.now().plusDays(1));

        BungalowRate result = rateService.updateRate(1L, updated);

        assertNotNull(result);
        assertEquals(150.0, result.getValue());
        verify(rateRepository, atLeast(2)).save(any(BungalowRate.class));
    }

    // ===============================
    // Test calculateStayPrice
    // ===============================
    @Test
    void calculateStayPrice_withValidRates_shouldReturnTotal() {
        BungalowRate rate = new BungalowRate();
        rate.setBungalowId(1);
        rate.setStayDateFrom(LocalDate.of(2025, 11, 1));
        rate.setStayDateTo(LocalDate.of(2025, 11, 3));
        rate.setValue(300.0);
        rate.setNights(1);
        rate.setBookingDateFrom(LocalDate.of(2025, 10, 1));
        rate.setBookingDateTo(null);

        when(rateRepository.findByBungalowIdOrderByStayDateFrom(1)).thenReturn(Collections.singletonList(rate));

        double total = rateService.calculateStayPrice(
                1L,
                LocalDate.of(2025, 11, 1),
                LocalDate.of(2025, 11, 4),
                LocalDate.of(2025, 10, 15)
        );

        assertEquals(900.0, total);
    }

    @Test
    void calculateStayPrice_noRate_shouldThrowException() {
        when(rateRepository.findByBungalowIdOrderByStayDateFrom(anyInt())).thenReturn(Collections.emptyList());

        assertThrows(RuntimeException.class, () -> rateService.calculateStayPrice(
                1L,
                LocalDate.of(2025, 11, 1),
                LocalDate.of(2025, 11, 4),
                LocalDate.of(2025, 10, 15)
        ));
    }

    // ===============================
    // Test removeRate
    // ===============================
    @Test
    void removeRate_existing_shouldDelete() {
        when(rateRepository.existsById(1)).thenReturn(true);
        doNothing().when(rateRepository).deleteById(1);

        rateService.removeRate(1L);
        verify(rateRepository, times(1)).deleteById(1);
    }

    @Test
    void removeRate_nonExisting_shouldThrowException() {
        when(rateRepository.existsById(1)).thenReturn(false);
        assertThrows(RuntimeException.class, () -> rateService.removeRate(1L));
    }

    // ===============================
    // Test closeRate
    // ===============================
    @Test
    void closeRate_existing_shouldSetBookingDateTo() {
        BungalowRate rate = new BungalowRate();
        rate.setRateId(1);
        rate.setBookingDateFrom(LocalDate.now());
        when(rateRepository.findById(1)).thenReturn(Optional.of(rate));
        when(rateRepository.save(rate)).thenReturn(rate);

        LocalDate endDate = LocalDate.now().plusDays(1);
        rateService.closeRate(1L, endDate);

        assertEquals(endDate, rate.getBookingDateTo());
        verify(rateRepository, times(1)).save(rate);
    }

    // ===============================
    // Test Excel export
    // ===============================
    @Test
    void exportRatesToExcel_shouldReturnData() throws Exception {
        BungalowRate rate = new BungalowRate();
        rate.setRateId(1);
        rate.setBungalowId(1);
        rate.setStayDateFrom(LocalDate.now());
        rate.setStayDateTo(LocalDate.now().plusDays(1));
        rate.setNights(1);
        rate.setValue(100.0);
        rate.setBookingDateFrom(LocalDate.now());

        when(rateRepository.findAll()).thenReturn(Collections.singletonList(rate));

        ByteArrayInputStream stream = rateService.exportRatesToExcel();
        assertNotNull(stream);
        assertTrue(stream.available() > 0);
    }
}


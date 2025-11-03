package com.example.demo.service;

import com.example.demo.entity.BungalowRate;
import com.example.demo.repository.RateRepository;
import com.example.demo.utils.Utils;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateServiceTest {

    @InjectMocks
    private RateService rateService;

    @Mock
    private RateRepository rateRepository;

    @Mock
    private Utils util;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
    }

    // ----------------------------------------------------------
    // getAllRates
    // ----------------------------------------------------------
    @Test
    void testGetAllRates_ShouldReturnAllRates() {
        List<BungalowRate> mockRates = List.of(new BungalowRate(), new BungalowRate());
        when(rateRepository.findAll()).thenReturn(mockRates);

        List<BungalowRate> result = rateService.getAllRates();

        assertEquals(2, result.size());
        verify(rateRepository, times(1)).findAll();
    }

    // ----------------------------------------------------------
    // getRateById
    // ----------------------------------------------------------
    @Test
    void testGetRateById_WhenFound() {
        BungalowRate rate = new BungalowRate();
        rate.setRateId(1);
        when(rateRepository.findById(1L)).thenReturn(Optional.of(rate));

        BungalowRate found = rateService.getRateById(1L);

        assertEquals(1, found.getRateId());
        verify(rateRepository).findById(1L);
    }

    @Test
    void testGetRateById_WhenNotFound() {
        when(rateRepository.findById(1L)).thenReturn(Optional.empty());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> rateService.getRateById(1L));

        assertEquals("Rate not found with id 1", ex.getMessage());
    }

    // ----------------------------------------------------------
    // createRate
    // ----------------------------------------------------------
    @Test
    void testCreateRate_ShouldPrepareSplitMergeAndSave() {
        BungalowRate rate = new BungalowRate();
        rate.setBungalowId(10);
        rate.setStayDateFrom(LocalDate.of(2025, 1, 1));
        rate.setStayDateTo(LocalDate.of(2025, 1, 5));

        when(rateRepository.save(any())).thenReturn(rate);

        BungalowRate result = rateService.createRate(rate);

        assertNotNull(result);
        verify(util).prepareNewRate(rate);
        verify(rateRepository).save(rate);
        verify(util, atLeast(0)).splitRateIfOverlapping(any(), any());
        verify(rateRepository, atLeast(1)).findByBungalowIdAndBookingDateToIsNullAndStayDateToGreaterThanEqualAndStayDateFromLessThanEqual(
                eq(10L), any(), any());
    }

    // ----------------------------------------------------------
    // closeRate
    // ----------------------------------------------------------
    @Test
    void testCloseRate_ShouldSetBookingDateTo() {
        BungalowRate rate = new BungalowRate();
        rate.setRateId(1);

        when(rateRepository.findById(1L)).thenReturn(Optional.of(rate));

        LocalDate today = LocalDate.now();
        rateService.closeRate(1L, today);

        assertEquals(today, rate.getBookingDateTo());
        verify(rateRepository).save(rate);
    }

    @Test
    void testCloseRate_NotFound() {
        when(rateRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> rateService.closeRate(99L, LocalDate.now()));
    }

    // ----------------------------------------------------------
    // deleteRate
    // ----------------------------------------------------------
    @Test
    void testDeleteRate_Success() {
        when(rateRepository.existsById(1L)).thenReturn(true);

        rateService.deleteRate(1L);

        verify(rateRepository).deleteById(1L);
    }

    @Test
    void testDeleteRate_NotFound() {
        when(rateRepository.existsById(1L)).thenReturn(false);
        assertThrows(RuntimeException.class, () -> rateService.deleteRate(1L));
    }

    // ----------------------------------------------------------
    // updateRate
    // ----------------------------------------------------------
    @Test
    void testUpdateRate_ShouldCloseOldAndCreateNew() {
        BungalowRate oldRate = new BungalowRate();
        oldRate.setRateId(1);
        oldRate.setBungalowId(20);

        when(rateRepository.findById(1L)).thenReturn(Optional.of(oldRate));
        when(rateRepository.save(any())).thenReturn(oldRate);

        BungalowRate updated = new BungalowRate();
        updated.setBungalowId(20);
        updated.setStayDateFrom(LocalDate.now());
        updated.setStayDateTo(LocalDate.now().plusDays(3));

        doNothing().when(util).prepareNewRate(any());

        rateService.updateRate(1L, updated);

        verify(rateRepository, times(1)).save(oldRate);
        verify(rateRepository, times(1)).save(updated);
    }

    // ----------------------------------------------------------
    // calculatePrice
    // ----------------------------------------------------------
    @Test
    void testCalculatePrice_ShouldReturnCorrectTotal() {
        BungalowRate rate = new BungalowRate();
        rate.setBungalowId(5);
        rate.setStayDateFrom(LocalDate.of(2025, 1, 1));
        rate.setStayDateTo(LocalDate.of(2025, 1, 10));
        rate.setBookingDateFrom(LocalDate.of(2024, 12, 1));
        rate.setNights(10);
        rate.setValue(1000.0);

        when(rateRepository.findByBungalowIdOrderByStayDateFrom(5L))
                .thenReturn(List.of(rate));
        when(util.findApplicableRate(any(), any(), any(), any())).thenReturn(rate);

        double total = rateService.calculatePrice(
                5L,
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 1, 4),
                LocalDate.of(2024, 12, 15)
        );

        // 3 nights × (1000 / 10 per night)
        assertEquals(300.0, total, 0.001);
    }

    @Test
    void testCalculatePrice_NoRateFound_ShouldThrow() {
        when(rateRepository.findByBungalowIdOrderByStayDateFrom(10L))
                .thenReturn(Collections.emptyList());

        assertThrows(RuntimeException.class, () ->
                rateService.calculatePrice(10L,
                        LocalDate.of(2025, 1, 1),
                        LocalDate.of(2025, 1, 3),
                        LocalDate.now()));
    }

    @Test
    void testCalculatePrice_InvalidDates_ShouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                rateService.calculatePrice(10L,
                        LocalDate.of(2025, 1, 5),
                        LocalDate.of(2025, 1, 1),
                        LocalDate.now()));
    }

    // ----------------------------------------------------------
    // exportRatesToExcel
    // ----------------------------------------------------------
    @Test
    void testExportRatesToExcel_ShouldGenerateExcel() throws IOException {
        BungalowRate rate = new BungalowRate();
        rate.setRateId(1);
        rate.setBungalowId(1);
        rate.setStayDateFrom(LocalDate.now());
        rate.setStayDateTo(LocalDate.now().plusDays(2));
        rate.setBookingDateFrom(LocalDate.now());
        rate.setNights(2);
        rate.setValue(200.0);

        when(rateRepository.findAll()).thenReturn(List.of(rate));

        ByteArrayInputStream stream = rateService.exportRatesToExcel();

        assertNotNull(stream);
        assertTrue(stream.available() > 0);
    }

    // ----------------------------------------------------------
    // importRatesFromExcel
    // ----------------------------------------------------------
    @Test
    void testImportRatesFromExcel_ShouldParseAndCreateRates() throws Exception {
        // Create mock workbook
        XSSFWorkbook workbook = new XSSFWorkbook();
        var sheet = workbook.createSheet();
        var header = sheet.createRow(0);
        header.createCell(0).setCellValue("ID");
        var row = sheet.createRow(1);
        row.createCell(0).setCellValue(1);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        workbook.close();

        MultipartFile mockFile = mock(MultipartFile.class);
        when(mockFile.getInputStream()).thenReturn(new ByteArrayInputStream(out.toByteArray()));

        // --- FIX: mock a valid rate with data ---
        BungalowRate rate = new BungalowRate();
        rate.setBungalowId(1);
        rate.setStayDateFrom(LocalDate.of(2025, 1, 1));
        rate.setStayDateTo(LocalDate.of(2025, 1, 5));
        rate.setNights(1);
        rate.setValue(1000.0);
        rate.setBookingDateFrom(LocalDate.of(2025, 1, 1));

        when(util.parseRowToBungalowRate(any())).thenReturn(rate);
        when(rateRepository.save(any())).thenReturn(rate);

        rateService.importRatesFromExcel(mockFile);

        verify(util, atLeastOnce()).parseRowToBungalowRate(any());
        verify(rateRepository, atLeastOnce()).save(any());
    }

}

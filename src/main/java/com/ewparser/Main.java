package com.ewparser;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.DateTime;
import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.TimeZoneRegistry;
import net.fortuna.ical4j.model.TimeZoneRegistryFactory;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripperByArea;

import java.awt.geom.Rectangle2D;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    private static final Map<String, String> dutyMap = new HashMap<>();
    private static final Map<String, String> iataMap = new HashMap<>();
    
    private static LocalDate currentDateReference = null;
    private static final Set<String> processedEvents = new HashSet<>();
    private static Calendar icsCalendar;
    private static TimeZoneRegistry registry;

    public static void main(String[] args) {
        loadConfig();
        
        icsCalendar = new Calendar();
        icsCalendar.getProperties().add(new ProdId("-//Eurowings Parser//iCal4j 1.0//EN"));
        icsCalendar.getProperties().add(Version.VERSION_2_0);
        icsCalendar.getProperties().add(CalScale.GREGORIAN);
        registry = TimeZoneRegistryFactory.getInstance().createRegistry();

        File pdfFile = new File("dutyplan.pdf");
        if (!pdfFile.exists()) { System.out.println("Error: dutyplan.pdf not found."); return; }

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            System.out.println("Processing PDF...");
            
            extractPeriodDate(document.getPage(0));
            if (currentDateReference == null) {
                System.out.println("Critical Error: Could not find Period Start Date!");
                return;
            }

            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
            stripper.setSortByPosition(true);

            Rectangle2D leftColumn = new Rectangle2D.Float(20, 120, 270, 650);
            Rectangle2D rightColumn = new Rectangle2D.Float(293, 120, 270, 650);
            stripper.addRegion("leftCol", leftColumn);
            stripper.addRegion("rightCol", rightColumn);

            Rectangle2D headerZone = new Rectangle2D.Float(0, 0, 600, 150);
            PDFTextStripperByArea headerStripper = new PDFTextStripperByArea();
            headerStripper.addRegion("header", headerZone);

            for (int i = 0; i < document.getNumberOfPages(); i++) {
                PDPage page = document.getPage(i);

                headerStripper.extractRegions(page);
                String headerText = headerStripper.getTextForRegion("header");
                
                if (headerText.contains("Standby points") || headerText.contains("Crew Information")) {
                    System.out.println("Skipping Page " + (i + 1) + " (Red Zone / Stats)");
                    continue;
                }

                stripper.extractRegions(page);
                
                String leftText = applyStopLogic(stripper.getTextForRegion("leftCol"));
                String rightText = applyStopLogic(stripper.getTextForRegion("rightCol"));
                
                parseRawText(leftText);
                parseRawText(rightText);
            }

            try (FileOutputStream fout = new FileOutputStream("roster.ics")) {
                net.fortuna.ical4j.data.CalendarOutputter outputter = new net.fortuna.ical4j.data.CalendarOutputter();
                outputter.output(icsCalendar, fout);
                System.out.println("\nSUCCESS! roster.ics created.");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String applyStopLogic(String text) {
        String[] stopKeywords = {"Vacation Claim", "Hotels", "Crew Information", "Standby points"};
        for (String keyword : stopKeywords) {
            int index = text.indexOf(keyword);
            if (index != -1) {
                return text.substring(0, index);
            }
        }
        return text;
    }

    private static void parseRawText(String rawText) {
        String[] lines = rawText.split("\\r?\\n");
        
        Pattern dayPattern = Pattern.compile("^((?:Mon|Tue|Wed|Thu|Fri|Sat|Sun))(\\d{2})(.*)");
        Pattern flightPattern = Pattern.compile("(EW)\\s?(\\d{2,4})\\s([A-Z]{3})\\s(\\d{4})\\s(\\d{4})\\s([A-Z]{3})");
        Pattern dutyPattern = Pattern.compile("(C/I|Pick Up|S/U)\\s?([A-Z]{3})?\\s?(\\d{4})");
        Pattern offPattern = Pattern.compile("(OFF|O_M|O_U|F|VAC|KCC-VAC|KCC-FLD|KCC-OFF|DISP|DISP_FIX|U|MEETING)\\s?([A-Z]{3})?");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            Matcher mDay = dayPattern.matcher(line);
            
            if (mDay.find()) {
                int dayNum = Integer.parseInt(mDay.group(2));
                updateCurrentDate(dayNum);
            } 
            else if (currentDateReference != null) {
                try {
                    Matcher mFlight = flightPattern.matcher(line);
                    Matcher mDuty = dutyPattern.matcher(line);
                    Matcher mOff = offPattern.matcher(line);

                    if (mFlight.find()) {
                        String flight = "EW " + mFlight.group(2);
                        String dep = mFlight.group(3);
                        String arr = mFlight.group(6);
                        String startT = mFlight.group(4);
                        String endT = mFlight.group(5);
                        addEvent(flight + " " + dep + "-" + arr, dep, startT, endT, "Flight");
                    } 
                    else if (mDuty.find()) {
                        String type = mDuty.group(1);
                        String loc = mDuty.group(2) != null ? mDuty.group(2) : "";
                        String time = mDuty.group(3);
                        String title = dutyMap.getOrDefault(type, type);
                        addEvent(title, loc, time, null, "Duty"); 
                    }
                    else if (mOff.find()) {
                        String code = mOff.group(1);
                        String title = dutyMap.getOrDefault(code, code);
                        addAllDayEvent(title);
                    }
                } catch (ParseException e) {
                    System.out.println("Error parsing event on line: " + line);
                }
            }
        }
    }

    private static void updateCurrentDate(int dayNum) {
        try {
            if (currentDateReference.getDayOfMonth() > dayNum) {
                currentDateReference = currentDateReference.plusMonths(1).withDayOfMonth(dayNum);
            } else {
                currentDateReference = currentDateReference.withDayOfMonth(dayNum);
            }
        } catch (Exception e) {
            System.out.println("Warning: Invalid Date Encountered for Day " + dayNum + " (Skipping update)");
        }
    }

    private static void addEvent(String title, String loc, String startS, String endS, String type) throws ParseException {
        String key = currentDateReference.toString() + "-" + title + "-" + startS;
        if (processedEvents.contains(key)) return;
        processedEvents.add(key);

        try {
            String region = iataMap.getOrDefault(loc, "UTC");
            TimeZone timezone = registry.getTimeZone(region);
            if (timezone == null) timezone = registry.getTimeZone("Europe/Berlin");

            LocalTime tStart = LocalTime.parse(startS, DateTimeFormatter.ofPattern("HHmm"));
            LocalDateTime dtStart = LocalDateTime.of(currentDateReference, tStart);
            DateTime icalStart = new DateTime(dtStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());

            VEvent event;
            if (endS != null) {
                LocalTime tEnd = LocalTime.parse(endS, DateTimeFormatter.ofPattern("HHmm"));
                LocalDateTime dtEnd = LocalDateTime.of(currentDateReference, tEnd);
                if (tEnd.isBefore(tStart)) dtEnd = dtEnd.plusDays(1);
                DateTime icalEnd = new DateTime(dtEnd.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
                event = new VEvent(icalStart, icalEnd, title);
            } else {
                DateTime icalEnd = new DateTime(dtStart.plusMinutes(30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
                event = new VEvent(icalStart, icalEnd, title);
            }

            event.getProperties().add(new Uid(UUID.randomUUID().toString()));
            event.getProperties().add(new Location(loc));
            icsCalendar.getComponents().add(event);
            System.out.println(" + Added: " + title + " on " + currentDateReference);

        } catch (Exception e) {
            System.out.println(" ! Error adding event: " + title);
        }
    }

    private static void addAllDayEvent(String title) throws ParseException {
        String key = currentDateReference.toString() + "-" + title;
        if (processedEvents.contains(key)) return;
        processedEvents.add(key);

        net.fortuna.ical4j.model.Date date = new net.fortuna.ical4j.model.Date(currentDateReference.toString().replace("-", ""));
        VEvent event = new VEvent(date, title);
        event.getProperties().add(new Uid(UUID.randomUUID().toString()));
        icsCalendar.getComponents().add(event);
        System.out.println(" + Added All-Day: " + title + " on " + currentDateReference);
    }

    private static void extractPeriodDate(PDPage page) throws IOException {
        PDFTextStripperByArea stripper = new PDFTextStripperByArea();
        stripper.addRegion("period", new Rectangle2D.Float(0, 0, 300, 100));
        stripper.extractRegions(page);
        String text = stripper.getTextForRegion("period");
        Pattern p = Pattern.compile("Period:\\s+([0-9]{2})([A-Za-z]{3})([0-9]{2})");
        Matcher m = p.matcher(text);
        if (m.find()) {
            int day = Integer.parseInt(m.group(1));
            String monthStr = m.group(2); 
            int year = 2000 + Integer.parseInt(m.group(3)); 
            int month = "JanFebMarAprMayJunJulAugSepOctNovDec".indexOf(monthStr) / 3 + 1;
            currentDateReference = LocalDate.of(year, month, day);
            System.out.println("Base Date Set: " + currentDateReference);
        }
    }

    private static void loadConfig() {
        loadMap("Dienste.txt", dutyMap);
        loadMap("IATA.csv", iataMap);
    }

    private static void loadMap(String file, Map<String, String> map) {
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(";");
                if (parts.length >= 2) map.put(parts[0].trim(), parts[1].trim());
            }
        } catch (Exception e) {
            System.out.println("Warning: Could not load " + file);
        }
    }
}
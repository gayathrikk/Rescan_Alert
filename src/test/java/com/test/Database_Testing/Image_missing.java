package com.test.Database_Testing;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import org.testng.annotations.Test;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

public class Image_missing {

    private Map<Integer, String> biosampleBrainNames = new HashMap<>();

    @Test
    public void testDBandListMissingFiles() {

        // Step 1: Connect to MySQL Database and retrieve biosample-series-section mappings
        Map<Integer, Map<String, List<Integer>>> biosampleSeriesSections = connectAndQueryDB();

        // Step 2: SSH Connection and check for missing lossless.jp2 files
        String host = "pp6.humanbrain.in";
        String user = "hbp";
        String password = "hbpsgbclab@123";
        String basePath = "/mnt/remote/analytics";

        checkMissingLosslessFiles(host, user, password, basePath, biosampleSeriesSections);
    }

    private Map<Integer, Map<String, List<Integer>>> connectAndQueryDB() {

        Map<Integer, Map<String, List<Integer>>> biosampleSeriesSections = new HashMap<>();

        String url = "jdbc:mysql://apollo2.humanbrain.in:3306/HBA_V2";
        String username = "root";
        String password = "Health#123";

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            System.out.println("Driver loaded");

            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                System.out.println("MySQL database connected");
                biosampleSeriesSections = executeAndPrintQuery(connection);
            }

        } catch (ClassNotFoundException e) {
            System.err.println("MySQL Driver not found: " + e.getMessage());

        } catch (SQLException e) {
            System.err.println("Database connection error: " + e.getMessage());
        }

        return biosampleSeriesSections;
    }

    private Map<Integer, Map<String, List<Integer>>> executeAndPrintQuery(Connection connection) {

        String query =
                "SELECT b.id AS biosample, sr.name AS series_name, "
                        + "s.positionindex AS section_no, ss.name AS brain_name "
                        + "FROM section s "
                        + "INNER JOIN series sr ON s.series = sr.id "
                        + "INNER JOIN seriesset ss ON sr.seriesset = ss.id "
                        + "INNER JOIN biosample b ON ss.biosample = b.id "
                        + "WHERE s.created_ts BETWEEN '2025-02-06 00:00:00' AND NOW() "
                        + "AND (s.jp2Path IS NULL OR s.jp2Path NOT LIKE '%BFI%') "
                        + "AND ( "
                        + "     sr.name LIKE '%NISL%' "
                        + "  OR sr.name LIKE '%HEOS%' "
                        + "  OR sr.name LIKE '%IHCS%' "
                        + "  OR sr.name LIKE '%MYEL%' "
                        + "  OR sr.name LIKE '%IHC1%' "
                        + "  OR sr.name LIKE '%IHC2%' "
                        + "  OR sr.name LIKE '%IHC3%' "
                        + "  OR sr.name LIKE '%IHC4%' "
                        + "  OR sr.name LIKE '%IHC5%' "
                        + "  OR sr.name LIKE '%IHC6%' "
                        + "  OR sr.name LIKE '%IHC7%' "
                        + "  OR sr.name LIKE '%IHC8%' "
                        + ")";

        Map<Integer, Map<String, List<Integer>>> biosampleSeriesSections = new HashMap<>();

        try (PreparedStatement statement = connection.prepareStatement(query);
             ResultSet resultSet = statement.executeQuery()) {

            boolean dataFound = false;

            System.out.printf("%-50s %-10s %-20s %-10s%n",
                    "Brain Name", "Biosample", "Series Name", "Section No");
            System.out.println("-".repeat(100));

            while (resultSet.next()) {

                int biosample = resultSet.getInt("biosample");

                // Exclude biosamples 580 and 437 completely
                if (biosample == 580 || biosample == 437) {
                    continue;
                }

                dataFound = true;

                String seriesName = resultSet.getString("series_name");
                int sectionNo = resultSet.getInt("section_no");
                String brainName = resultSet.getString("brain_name");

                // Print query results
                System.out.printf("%-50s %-10d %-20s %-10d%n",
                        brainName, biosample, seriesName, sectionNo);

                // Store brain name for each biosample
                biosampleBrainNames.put(biosample, brainName);

                // Extract suffix from series name
                String suffix = seriesName.contains("_")
                        ? seriesName.split("_", 2)[1]
                        : seriesName;

                // Skip v11 series
                if ("v11".equalsIgnoreCase(suffix)) {
                    continue;
                }

                // Populate map
                biosampleSeriesSections
                        .computeIfAbsent(biosample, k -> new HashMap<>())
                        .computeIfAbsent(suffix, k -> new ArrayList<>())
                        .add(sectionNo);
            }

            if (!dataFound) {
                System.out.println("No records found for the specified date.");
            }

        } catch (SQLException e) {
            System.err.println("SQL query execution error: " + e.getMessage());
        }

        return biosampleSeriesSections;
    }

    private void checkMissingLosslessFiles(
            String host,
            String user,
            String password,
            String basePath,
            Map<Integer, Map<String, List<Integer>>> biosampleSeriesSections) {

        // Fully qualified name avoids conflict with javax.mail.Session
        com.jcraft.jsch.Session session = null;

        Map<String, List<Integer>> missingSections = new HashMap<>();

        try {
            JSch jsch = new JSch();

            session = jsch.getSession(user, host, 22);
            session.setPassword(password);
            session.setConfig("StrictHostKeyChecking", "no");
            session.connect();

            System.out.println("\nConnected to " + host);

            for (Map.Entry<Integer, Map<String, List<Integer>>> entry
                    : biosampleSeriesSections.entrySet()) {

                int biosample = entry.getKey();

                for (Map.Entry<String, List<Integer>> seriesEntry
                        : entry.getValue().entrySet()) {

                    String suffix = seriesEntry.getKey();
                    String remotePath = basePath + "/" + biosample + "/" + suffix;

                    for (int sectionNo : seriesEntry.getValue()) {

                        boolean fileExists = false;

                        String[] filePatterns = {
                                "_" + sectionNo + "_lossless.jp2",
                                "_" + sectionNo + "_Rescan01_lossless.jp2",
                                "_" + sectionNo + "-MR_[0-9]*_lossless.jp2",
                                "_" + sectionNo + "_R01_lossless.jp2",
                                "_" + sectionNo + "_Rescan02_lossless.jp2",
                                "_" + sectionNo + "_R02_lossless.jp2",
                                "_" + sectionNo + "_Slide01_lossless.jp2",
                                "_" + sectionNo + "_Slide02_lossless.jp2"
                        };

                        for (String pattern : filePatterns) {
                            String command = "ls " + remotePath + " | grep '" + pattern + "'";

                            if (executeRemoteCommand(session, command)) {
                                fileExists = true;
                                break;
                            }
                        }

                        if (!fileExists) {
                            System.out.println(
                                    "Missing lossless.jp2 for section "
                                            + sectionNo + " in " + remotePath);

                            missingSections
                                    .computeIfAbsent(
                                            "Biosample " + biosample + " (" + suffix + ")",
                                            k -> new ArrayList<>())
                                    .add(sectionNo);
                        }
                    }
                }
            }

            if (!missingSections.isEmpty()) {
                sendEmailAlert(missingSections, biosampleBrainNames);
            } else {
                System.out.println("No missing lossless.jp2 files found.");
            }

        } catch (JSchException e) {
            System.err.println("SSH Connection error: " + e.getMessage());

        } finally {
            if (session != null) {
                session.disconnect();
            }
        }
    }

    private boolean executeRemoteCommand(
            com.jcraft.jsch.Session session,
            String command) {

        ChannelExec channelExec = null;

        try {
            channelExec = (ChannelExec) session.openChannel("exec");
            channelExec.setCommand(command);
            channelExec.setInputStream(null);
            channelExec.setErrStream(System.err);

            InputStream input = channelExec.getInputStream();
            channelExec.connect();

            Scanner scanner = new Scanner(input);
            boolean fileFound = scanner.hasNextLine();
            scanner.close();

            return fileFound;

        } catch (Exception e) {
            System.err.println("Error executing command: " + command + " - " + e.getMessage());
            return false;

        } finally {
            if (channelExec != null) {
                channelExec.disconnect();
            }
        }
    }

    private void sendEmailAlert(
            Map<String, List<Integer>> missingSections,
            Map<Integer, String> biosampleBrainNames) {

        String[] to = {
                 "karthik6595@gmail.com",
                 "sindhu.r@htic.iitm.ac.in"
        };

        String[] cc = {
               "richavermaj@gmail.com", 
               "nathan.i@htic.iitm.ac.in", 
               "divya.d@htic.iitm.ac.in", 
               "venip@htic.iitm.ac.in", 
               "meena@htic.iitm.ac.in",
               "nitheshkumarsundhar@gmail.com",
               "manjukeerthi03@gmail.com"
        };

        String from = "automationsoftware25@gmail.com";
        String password = "cbsiopyovcrwyblp";
        String host = "smtp.gmail.com";

        Properties properties = System.getProperties();
        properties.put("mail.smtp.host", host);
        properties.put("mail.smtp.port", "465");
        properties.put("mail.smtp.ssl.enable", "true");
        properties.put("mail.smtp.auth", "true");

        javax.mail.Session mailSession = javax.mail.Session.getInstance(
                properties,
                new javax.mail.Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(from, password);
                    }
                });

        try {
            MimeMessage message = new MimeMessage(mailSession);

            message.setFrom(new InternetAddress(from));

            for (String recipient : to) {
                message.addRecipient(
                        Message.RecipientType.TO,
                        new InternetAddress(recipient));
            }

            for (String ccRecipient : cc) {
                message.addRecipient(
                        Message.RecipientType.CC,
                        new InternetAddress(ccRecipient));
            }

            message.setSubject("Alert: Rescan Issues");

            StringBuilder emailBody = new StringBuilder("<html><body>");

            emailBody.append("<b>This is an automatically generated email,</b><br><br>");
            emailBody.append("For your attention and action:<br>");
            emailBody.append("<h3>The following images are missing on the viewer page</h3>");

            emailBody.append("<table border='1'>")
                    .append("<tr>")
                    .append("<th>Brain Name</th>")
                    .append("<th>Biosample (Series)</th>")
                    .append("<th>Missing Sections</th>")
                    .append("</tr>");

            for (Map.Entry<String, List<Integer>> entry : missingSections.entrySet()) {

                String biosampleSeries = entry.getKey();
                String brainName = "Unknown";

                String[] parts = biosampleSeries.split(" ");

                if (parts.length > 1) {
                    try {
                        int biosample = Integer.parseInt(parts[1]);
                        brainName = biosampleBrainNames.getOrDefault(biosample, "Unknown");
                    } catch (NumberFormatException ignored) {
                        // Keep brain name as Unknown
                    }
                }

                emailBody.append("<tr>")
                        .append("<td>").append(brainName).append("</td>")
                        .append("<td>").append(biosampleSeries).append("</td>")
                        .append("<td>").append(entry.getValue()).append("</td>")
                        .append("</tr>");
            }

            emailBody.append("</table></body></html>");

            message.setContent(emailBody.toString(), "text/html");

            Transport.send(message);

            System.out.println("Email sent successfully!");

        } catch (MessagingException e) {
            e.printStackTrace();
        }
    }
}

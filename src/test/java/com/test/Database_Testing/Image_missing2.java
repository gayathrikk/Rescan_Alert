package com.test.Database_Testing;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Date;
import java.util.*;
import javax.mail.*;
import javax.mail.internet.*;
import java.util.Properties;

public class Image_missing2 {
    public static void main(String[] args) {
        // Database credentials
        String url = "jdbc:mysql://apollo2.humanbrain.in:3306/HBA_V2";
        String username = "root";
        String password = "Health#123";

        // SQL Query
        String query = "SELECT sb.id, sb.name, sb.datalocation, sb.process_status, sb.arrival_date, s.filename "
                + "FROM `slidebatch` sb "
                + "JOIN `slide` s ON sb.id = s.slidebatch "
                + "WHERE DATE(sb.arrival_date) IN (CURDATE(), DATE_SUB(CURDATE(), INTERVAL 3 DAY))";

        try (Connection conn = DriverManager.getConnection(url, username, password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            Map<String, List<String>> filenameRecordMap = new HashMap<>();
            Map<String, Integer> filenameCountMap = new HashMap<>();

            while (rs.next()) {
                int batchId = rs.getInt("id");
                String name = rs.getString("name");
                String datalocation = rs.getString("datalocation");
                int processStatus = rs.getInt("process_status");
                Date arrivalDate = rs.getDate("arrival_date");
                String filename = rs.getString("filename");

                // Include process status in console record
                String consoleRecord = String.format("%-10d %-40s %-30s %-20d %-20s %-30s",
                        batchId, name, datalocation, processStatus, arrivalDate, filename);

                // Exclude process status from email record
                String emailRecord = String.format("%-10d %-40s %-30s %-20s %-30s",
                        batchId, name, datalocation, arrivalDate, filename);

                filenameRecordMap.computeIfAbsent(filename, k -> new ArrayList<>()).add(consoleRecord + "|" + emailRecord);
                filenameCountMap.put(filename, filenameCountMap.getOrDefault(filename, 0) + 1);
            }

            Set<String> repeatedFilenames = new HashSet<>();
            for (Map.Entry<String, Integer> entry : filenameCountMap.entrySet()) {
                if (entry.getValue() > 1) {
                    repeatedFilenames.add(entry.getKey());
                }
            }

            if (!repeatedFilenames.isEmpty()) {
                StringBuilder consoleOutput = new StringBuilder();
                StringBuilder emailBody = new StringBuilder();

                consoleOutput.append(String.format("%-10s %-40s %-30s %-20s %-20s %-30s\n",
                        "Batch ID", "Name", "Data Location", "Process Status", "Arrival Date", "Filename"));
                consoleOutput.append("---------------------------------------------------------------------------------------------------------------------------\n");

                emailBody.append("<html><body>");
                emailBody.append("<p>This is an automatically generated email,<br>For your attention and action:</p>");
                emailBody.append("<p><strong>Alert:</strong> The following images have multiple scans with pending processing.</p>");

                for (String filename : repeatedFilenames) {
                    consoleOutput.append("\nFilename: ").append(filename).append("\n");
                    consoleOutput.append("---------------------------------------------------------------------------------------------------------------------------\n");

                    emailBody.append("<p><strong>Filename:</strong> ").append(filename).append("</p>");
                    emailBody.append("<table border='1' cellpadding='5' cellspacing='0' style='border-collapse:collapse;'>");
                    emailBody.append("<tr><th>Batch ID</th><th>Name</th><th>Data Location</th><th>Arrival Date</th><th>Filename</th></tr>");

                    for (String combinedRecord : filenameRecordMap.get(filename)) {
                        String[] records = combinedRecord.split("\\|");
                        String consoleRecord = records[0];
                        String emailRecord = records[1];

                        // Print full details (including process status) to console
                        consoleOutput.append(consoleRecord).append("\n");

                        // Populate email table without process status
                        String[] emailParts = emailRecord.trim().split("\\s{2,}");
                        emailBody.append("<tr>")
                                 .append("<td>").append(emailParts[0]).append("</td>")
                                 .append("<td>").append(emailParts[1]).append("</td>")
                                 .append("<td>").append(emailParts[2]).append("</td>")
                                 .append("<td>").append(emailParts[3]).append("</td>")
                                 .append("<td>").append(emailParts[4]).append("</td>")
                                 .append("</tr>");
                    }

                    emailBody.append("</table><br>"); // Space between groups of filenames
                }

                emailBody.append("<p><strong>Note:</strong> Please rescan the images only after the previous ones reach the out stages.</p>");
                emailBody.append("</body></html>");

                System.out.println(consoleOutput.toString()); // Print output with process status to console
                sendEmailAlert(emailBody.toString()); // Send email excluding process status

            } else {
                System.out.println("No repeated filenames detected for the given date range.");
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void sendEmailAlert(String messageBody) {
    	String[] to = {"karthik6595@gmail.com",
    			       "sindhu.r@htic.iitm.ac.in"};
    			       
        String[] cc = {"richavermaj@gmail.com", 
        		       "nathan.i@htic.iitm.ac.in", 
        		       "divya.d@htic.iitm.ac.in", 
        		       "venip@htic.iitm.ac.in"};

        String from = "gayathri@htic.iitm.ac.in";
        final String emailUser = "gayathri@htic.iitm.ac.in";
        final String emailPassword = "Gayu@0918";

        String host = "smtp.gmail.com";
        Properties properties = new Properties();
        properties.put("mail.smtp.host", host);
        properties.put("mail.smtp.port", "465");
        properties.put("mail.smtp.ssl.enable", "true");
        properties.put("mail.smtp.auth", "true");

        Session session = Session.getInstance(properties, new javax.mail.Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(emailUser, emailPassword);
            }
        });

        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from));

            for (String recipient : to) {
                message.addRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
            }
            
            for (String ccRecipient : cc) {
                message.addRecipient(Message.RecipientType.CC, new InternetAddress(ccRecipient));
            }

            message.setSubject("Scanning Alert");
            message.setContent(messageBody, "text/html");

            System.out.println("Sending alert email...");
            Transport.send(message);
            System.out.println("Email sent successfully.");

        } catch (MessagingException mex) {
            mex.printStackTrace();
        }
    }
}

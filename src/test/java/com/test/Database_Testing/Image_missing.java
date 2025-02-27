package com.test.Database_Testing;

import com.jcraft.jsch.*;
import org.testng.annotations.Test;
import java.io.InputStream;
import java.sql.*;
import java.util.*;
import javax.mail.*;
import javax.mail.internet.*;

public class Image_missing {

    @Test
    public void testDBandListMissingFiles() {
        // Step 1: Connect to MySQL Database and retrieve biosample-series-section mappings
        Map<Integer, Map<String, List<Integer>>> biosampleSeriesSections = connectAndQueryDB();

        // Step 2: SSH Connection and check for missing lossless.jp2 files
        String host = "pp6.humanbrain.in";
        String user = "hbp";
        String password = "Health#123"; // ⚠ Move this to a secure location.
        String basePath = "/lustre/data/store10PB/repos1/iitlab/humanbrain/analytics";

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

    private Map<Integer, String> biosampleBrainNames = new HashMap<>(); // Stores biosample -> brain name

    private Map<Integer, Map<String, List<Integer>>> executeAndPrintQuery(Connection connection) {
        String query = "SELECT b.id AS biosample, sr.name AS series_name, s.positionindex AS section_no, ss.name AS brain_name " +
                       "FROM section s " +
                       "INNER JOIN series sr ON s.series = sr.id " +
                       "INNER JOIN seriesset ss ON sr.seriesset = ss.id " +
                       "INNER JOIN biosample b ON ss.biosample = b.id " +
                       "WHERE s.created_ts BETWEEN '2025-02-06 00:00:00' AND NOW() " +
                       "AND (s.jp2Path IS NULL OR s.jp2Path NOT LIKE '%BFI%')";

        Map<Integer, Map<String, List<Integer>>> biosampleSeriesSections = new HashMap<>();

        try (PreparedStatement statement = connection.prepareStatement(query);
             ResultSet resultSet = statement.executeQuery()) {

            boolean dataFound = false;
            System.out.printf("%-20s %-10s %-20s %-10s%n", "Brain Name", "Biosample", "Series Name", "Section No");
            System.out.println("-".repeat(65));

            while (resultSet.next()) {
                dataFound = true;

                int biosample = resultSet.getInt("biosample");
                String seriesName = resultSet.getString("series_name");
                int sectionNo = resultSet.getInt("section_no");
                String brainName = resultSet.getString("brain_name");  // Extract Brain Name

                // Print the query results
                System.out.printf("%-50s %-10d %-20s %-10d%n", brainName, biosample, seriesName, sectionNo);

                // Store Brain Name for each Biosample
                biosampleBrainNames.put(biosample, brainName);

                // Extract suffix from series name
                String suffix = seriesName.contains("_") ? seriesName.split("_", 2)[1] : seriesName;

                // Populate the map
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



	 private void checkMissingLosslessFiles(String host, String user, String password, String basePath, 
			            Map<Integer, Map<String, List<Integer>>> biosampleSeriesSections) {
			com.jcraft.jsch.Session session = null;
			Map<String, List<Integer>> missingSections = new HashMap<>();
			
			try {
				JSch jsch = new JSch();
				session = jsch.getSession(user, host, 22);
				session.setPassword(password);
				session.setConfig("StrictHostKeyChecking", "no");
				session.connect();
				System.out.println("\nConnected to " + host);
			
			for (Map.Entry<Integer, Map<String, List<Integer>>> entry : biosampleSeriesSections.entrySet()) {
			int biosample = entry.getKey();
			
			for (Map.Entry<String, List<Integer>> seriesEntry : entry.getValue().entrySet()) {
				String suffix = seriesEntry.getKey();
				String remotePath = basePath + "/" + biosample + "/" + suffix;
			
			for (int sectionNo : seriesEntry.getValue()) {
				String command1 = "ls " + remotePath + " | grep '_" + sectionNo + "_lossless.jp2'";
				boolean fileExists = executeRemoteCommand(session, command1);
			
			if (!fileExists) {
				String command2 = "ls " + remotePath + " | grep '_" + sectionNo + "_Rescan01_lossless.jp2'";
				fileExists = executeRemoteCommand(session, command2);
			}
			
			if (!fileExists) {
				System.out.println("Missing lossless.jp2 for section " + sectionNo + " in " + remotePath);
				missingSections.computeIfAbsent("Biosample " + biosample + " (" + suffix + ")", k -> new ArrayList<>()).add(sectionNo);
			}
			}
			}
			}
			
			if (!missingSections.isEmpty()) {
			sendEmailAlert(missingSections, biosampleBrainNames);  // Pass Brain Name data
			}
			
			} catch (JSchException e) {
			System.err.println("SSH Connection error: " + e.getMessage());
			} finally {
			if (session != null) session.disconnect();
			}
			}


    private boolean executeRemoteCommand(com.jcraft.jsch.Session session, String command) {
        try {
            ChannelExec channelExec = (ChannelExec) session.openChannel("exec");
            channelExec.setCommand(command);
            channelExec.setInputStream(null);
            channelExec.setErrStream(System.err);

            InputStream input = channelExec.getInputStream();
            channelExec.connect();

            Scanner scanner = new Scanner(input);
            boolean fileFound = scanner.hasNextLine();
            scanner.close();

            channelExec.disconnect();
            return fileFound;
        } catch (Exception e) {
            System.err.println("Error executing command: " + command + " - " + e.getMessage());
            return false;
        }
    }

    private void sendEmailAlert(Map<String, List<Integer>> missingSections, Map<Integer, String> biosampleBrainNames) {
    	String[] to = {"karthik6595@gmail.com", "sindhu.r@htic.iitm.ac.in"};
        String[] cc = {"richavermaj@gmail.com", "nathan.i@htic.iitm.ac.in", "divya.d@htic.iitm.ac.in", "venip@htic.iitm.ac.in"};
        String from = "gayathri@htic.iitm.ac.in";
        String password = "Gayu@0918"; 
        String host = "smtp.gmail.com";

        Properties properties = System.getProperties();
        properties.put("mail.smtp.host", host);
        properties.put("mail.smtp.port", "465");
        properties.put("mail.smtp.ssl.enable", "true");
        properties.put("mail.smtp.auth", "true");

        javax.mail.Session mailSession = javax.mail.Session.getInstance(properties, new javax.mail.Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(from, password);
            }
        });

        try {
            MimeMessage message = new MimeMessage(mailSession);
            message.setFrom(new InternetAddress(from));
            for (String recipient : to) {
                message.addRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
            }
           
            for (String ccRecipient : cc) {
                message.addRecipient(Message.RecipientType.CC, new InternetAddress(ccRecipient));
            }
            message.setSubject("Alert: Rescan Issues");

            StringBuilder emailBody = new StringBuilder("<html><body>");
            emailBody.append("<b>This is an automatically generated email,</b><br><br>");
            emailBody.append("For your attention and action:<br>");
            emailBody.append("<h3>The following images are missing on the viewer page</h3>");
            emailBody.append("<table border='1'>")
                     .append("<tr><th>Brain Name</th><th>Biosample (Series)</th><th>Missing Sections</th></tr>");

            for (Map.Entry<String, List<Integer>> entry : missingSections.entrySet()) {
                String biosampleSeries = entry.getKey();
                String brainName = "Unknown"; 

                // Extract biosample ID from the key
                String[] parts = biosampleSeries.split(" ");
                if (parts.length > 1) {
                    try {
                        int biosample = Integer.parseInt(parts[1]);
                        brainName = biosampleBrainNames.getOrDefault(biosample, "Unknown");
                    } catch (NumberFormatException ignored) { }
                }

                emailBody.append("<tr>")
                         .append("<td>").append(brainName)
                         .append("<td>").append(biosampleSeries)
                         .append("<td>").append(entry.getValue())
                         .append("</tr>");
            }

            emailBody.append("</table></body></html>");

            message.setContent(emailBody.toString(), "text/html");

            Transport.send(message);
            System.out.println("Email sent successfully !");

        } catch (MessagingException e) {
            e.printStackTrace();
        }
    }

}

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ltc {
    // HTTP Headers
    private static final String HEADER_HOST = "Host: ";
    private static final String HEADER_CONNECTION = "Connection: ";
    private static final String HEADER_CONTENT_LENGTH = "Content-Length: ";
    private static final String HEADER_PAYLOAD_HASH = "X-Payload-Hash: ";

    // HTTP Values
    private static final String CONNECTION_KEEP_ALIVE = "keep-alive";

    private String host;
    private int port;
    private int connectionCount;
    private int requestsPerConnection;
    private int payloadSize;
    private int delayAfterRequests = 0; // Delay in seconds before closing connection

    private List<Long> latencies = new CopyOnWriteArrayList<>();
    private AtomicInteger totalRequests = new AtomicInteger(0);
    private AtomicInteger successfulRequests = new AtomicInteger(0);
    private AtomicInteger failedRequests = new AtomicInteger(0);

    public static void main(String[] args) {
        new ltc().appMain(args);
    }

    public void appMain(String[] args) {
        parseArguments(args);

        System.out.println("Load Test Configuration:");
        System.out.println("  Target: " + host + ":" + port);
        System.out.println("  Connections: " + connectionCount);
        System.out.println("  Requests per connection: " + requestsPerConnection);
        System.out.println("  Payload size: " + payloadSize + " bytes");
        System.out.println();

        long startTime = System.currentTimeMillis();
        runLoadTest();
        long endTime = System.currentTimeMillis();

        printResults(startTime, endTime);
    }

    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // Configuration
    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // parseArguments
    //
    // Parses command-line arguments and sets instance variables. Defaults
    // are provided for all parameters.
    //
    private void parseArguments(String[] args) {
        host = "localhost";
        port = 8080;
        connectionCount = 10;
        requestsPerConnection = 1;
        payloadSize = 1000;
        
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-h":
                case "--host":
                    if (i + 1 < args.length) {
                        host = args[++i];
                    }
                    break;
                case "-p":
                case "--port":
                    if (i + 1 < args.length) {
                        port = Integer.parseInt(args[++i]);
                    }
                    break;
                case "-c":
                case "--connections":
                    if (i + 1 < args.length) {
                        connectionCount = Integer.parseInt(args[++i]);
                    }
                    break;
                case "-r":
                case "--requests":
                    if (i + 1 < args.length) {
                        requestsPerConnection = Integer.parseInt(args[++i]);
                    }
                    break;
                case "-s":
                case "--size":
                    if (i + 1 < args.length) {
                        payloadSize = Integer.parseInt(args[++i]);
                    }
                    break;
                case "-d":
                case "--delay":
                    if (i + 1 < args.length) {
                        delayAfterRequests = Integer.parseInt(args[++i]);
                    }
                    break;
                case "--help":
                    printUsage();
                    System.exit(0);
                    break;
            }
        }
    }

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // printUsage
    //
    // Displays command-line help with available options and defaults.
    //
    private void printUsage() {
        System.out.println("Usage: java ltc [options]");
        System.out.println("Options:");
        System.out.println("  -h, --host <host>          Target host (default: localhost)");
        System.out.println("  -p, --port <port>          Target port (default: 8080)");
        System.out.println("  -c, --connections <count>  Number of connections (default: 10)");
        System.out.println("  -r, --requests <count>     Requests per connection (default: 1)");
        System.out.println("  -s, --size <bytes>         Payload size (default: 1000)");
        System.out.println("  -d, --delay <seconds>      Delay after requests before closing (default: 0)");
        System.out.println("  --help                     Show this help message");
    }

    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // Test Execution
    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // runLoadTest
    //
    // Creates virtual threads for each connection and waits for all to
    // complete. Virtual threads enable high concurrency without platform
    // thread overhead.
    //
    private void runLoadTest() {
        // Store thread references so we can join them later
        List<Thread> threads = new ArrayList<>();
        
        // Create and start all virtual threads
        // This is nearly identical to platform threads, just using Thread.ofVirtual()
        // instead of new Thread()
        //
        for (int i = 0; i < connectionCount; i++) {
            final int connectionId = i;  // final required for closure capture
            threads.add(Thread.ofVirtual().start(new ConnectionRunner(connectionId)));
        }
        
        // Wait for all threads to complete, just like pthread_join() in C (as taught in Unix/OS)
        // Each join() blocks until that specific thread finishes
        //
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                System.err.println("Load test interrupted");
            }
        }
    }

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // ConnectionRunner
    //
    // Runnable wrapper that executes a single connection's requests in a
    // virtual thread. Catches and reports any connection failures.
    //
    private class ConnectionRunner implements Runnable {
        private final int connectionId;
        
        public ConnectionRunner(int connectionId) {
            this.connectionId = connectionId;
        }
        
        public void run() {
            try {
                runConnection(connectionId);
            } catch (Exception e) {
                System.err.println("Connection " + connectionId + " failed: " + e.getMessage());
            }
        }
    }

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // runConnection
    //
    // Opens a socket, sends the configured number of requests over a single
    // keep-alive connection, and tracks latency metrics for each request.
    //
    private void runConnection(int connectionId) throws IOException {
        try (Socket socket = new Socket(host, port)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

            for (int i = 0; i < requestsPerConnection; i++) {
                long startTime = System.nanoTime();

                boolean success = sendRequest(out, in);

                long endTime = System.nanoTime();
                long latencyMs = (endTime - startTime) / 1_000_000;

                totalRequests.incrementAndGet();
                if (success) {
                    successfulRequests.incrementAndGet();
                    latencies.add(latencyMs);
                } else {
                    failedRequests.incrementAndGet();
                }
            }

            // Keep connection open to test server timeout
            if (delayAfterRequests > 0) {
                try {
                    Thread.sleep(delayAfterRequests * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // HTTP Protocol
    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // sendRequest
    //
    // Orchestrates sending an HTTP request and receiving/validating the
    // response. Returns true if request succeeded and hash verification
    // passed, false otherwise.
    //
    private boolean sendRequest(PrintWriter out, BufferedReader in) throws IOException {
        // Send the HTTP request
        sendHttpRequest(out);

        // Read and validate status line
        if (!readStatusLine(in)) {
            return false;
        }

        // Read and parse response headers
        ResponseHeaders headers = readResponseHeaders(in);
        if (headers == null || headers.contentLength < 0) {
            return false;
        }

        // Read response body
        String payload = readResponseBody(in, headers.contentLength);
        if (payload == null) {
            return false;
        }

        // Verify hash if provided
        if (headers.expectedHash != null) {
            if (!verifyHash(headers.expectedHash, payload)) {
                return false;
            }
        }

        return true;
    }

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // sendHttpRequest
    //
    // Sends an HTTP GET request for the /echo endpoint with keep-alive
    // connection header.
    //
    private void sendHttpRequest(PrintWriter out) {
        out.print("GET /echo/" + payloadSize + " HTTP/1.1\r\n");
        out.print(HEADER_HOST + host + "\r\n");
        out.print(HEADER_CONNECTION + CONNECTION_KEEP_ALIVE + "\r\n");
        out.print("\r\n");
        out.flush();
    }

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // readStatusLine
    //
    // Reads the HTTP status line and returns true if status is 200 OK.
    //
    private boolean readStatusLine(BufferedReader in) throws IOException {
        String statusLine = in.readLine();
        return statusLine != null && statusLine.contains("200");
    }

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // readResponseHeaders
    //
    // Reads HTTP response headers and extracts Content-Length and
    // X-Payload-Hash values. Returns null if headers cannot be read.
    //
    private ResponseHeaders readResponseHeaders(BufferedReader in) throws IOException {
        String expectedHash = null;
        int contentLength = -1;
        String line;

        while ((line = in.readLine()) != null && !line.isEmpty()) {
            if (line.startsWith(HEADER_PAYLOAD_HASH)) {
                expectedHash = line.substring(HEADER_PAYLOAD_HASH.length());
            } else if (line.startsWith(HEADER_CONTENT_LENGTH)) {
                contentLength = Integer.parseInt(line.substring(HEADER_CONTENT_LENGTH.length()).trim());
            }
        }

        return new ResponseHeaders(expectedHash, contentLength);
    }

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // readResponseBody
    //
    // Reads exactly contentLength characters from the input stream and
    // returns as a String. Returns null if connection closes prematurely.
    //
    private String readResponseBody(BufferedReader in, int contentLength) throws IOException {
        char[] buffer = new char[contentLength];
        int totalRead = 0;

        while (totalRead < contentLength) {
            int read = in.read(buffer, totalRead, contentLength - totalRead);
            if (read == -1) {
                return null;
            }
            totalRead += read;
        }

        return new String(buffer);
    }

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // verifyHash
    //
    // Computes SHA-256 hash of the payload and compares to expected hash.
    // Returns true if hashes match, false otherwise. Logs mismatch errors.
    //
    private boolean verifyHash(String expectedHash, String payload) {
        String actualHash = computeHash(payload.getBytes());
        if (!expectedHash.equals(actualHash)) {
            System.err.println("Hash mismatch! Expected: " + expectedHash + ", Got: " + actualHash);
            return false;
        }
        return true;
    }

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // ResponseHeaders
    //
    // Simple container for response header values we care about:
    // Content-Length and X-Payload-Hash.
    //
    private static class ResponseHeaders {
        final String expectedHash;
        final int contentLength;

        ResponseHeaders(String expectedHash, int contentLength) {
            this.expectedHash = expectedHash;
            this.contentLength = contentLength;
        }
    }

    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // Utilities & Results
    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // computeHash
    //
    // Computes SHA-256 hash of the given byte array and returns it as a
    // hex string. Returns null if SHA-256 algorithm is not available.
    //
    private String computeHash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
    
    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // printResults
    //
    // Prints summary statistics including total/successful/failed requests,
    // throughput (requests/second), and latency percentiles.
    //
    private void printResults(long startTime, long endTime) {
        double durationSeconds = (endTime - startTime) / 1000.0;
        double requestsPerSecond = totalRequests.get() / durationSeconds;

        System.out.println("\nLoad Test Results:");
        System.out.println("==================");
        System.out.println("Total requests: " + totalRequests.get());
        System.out.println("Successful: " + successfulRequests.get());
        System.out.println("Failed: " + failedRequests.get());
        System.out.println("Duration: " + String.format("%.2f", durationSeconds) + " seconds");
        System.out.println("Requests/second: " + String.format("%.2f", requestsPerSecond));

        if (!latencies.isEmpty()) {
            Collections.sort(latencies);
            System.out.println("\nLatency Percentiles (ms):");
            System.out.println("  Min: " + latencies.get(0));
            System.out.println("  p50: " + getPercentile(latencies, 50));
            System.out.println("  p90: " + getPercentile(latencies, 90));
            System.out.println("  p95: " + getPercentile(latencies, 95));
            System.out.println("  p99: " + getPercentile(latencies, 99));
            System.out.println("  Max: " + latencies.get(latencies.size() - 1));

            double avgLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
            System.out.println("  Avg: " + String.format("%.2f", avgLatency));
        }
    }

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // getPercentile
    //
    // Calculates the specified percentile from a sorted list of latencies.
    // For example, percentile=90 returns the 90th percentile value.
    //
    private long getPercentile(List<Long> sortedList, int percentile) {
        int index = (int) Math.ceil(sortedList.size() * percentile / 100.0) - 1;
        return sortedList.get(Math.max(0, Math.min(index, sortedList.size() - 1)));
    }
}
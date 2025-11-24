import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

//============================================================================
// lts - Load Test Server
//
// A multi-phase HTTP/1.1 server implementation for teaching computer
// networking concepts. Supports basic request/response, persistent
// connections with keep-alive, and virtual thread concurrency.
//
// Phase 1: Basic HTTP server with GET requests and static file serving
// Phase 2: HTTP/1.1 keep-alive with connection persistence
// Phase 3: Virtual threading for high-concurrency workloads
//
// Usage: java lts.java [options] [port]
//   -t            Enable virtual threading (Java 21+)
//   -k [timeout]  Enable keep-alive with optional timeout
//   -q            Quiet mode (disable request logging)
//   -h, --help    Show usage information
//
//============================================================================

public class lts {
    private static final int DEFAULT_PORT = 8080;
    private static final String PUBLIC_DIR = "public";
    private static final int DEFAULT_KEEPALIVE_TIMEOUT = 5;

    private boolean quiet = false;
    private boolean keepAlive = false;
    private int keepAliveTimeout = DEFAULT_KEEPALIVE_TIMEOUT;

    public static void main(String[] args) {
        new lts().appMain(args);
    }

    public void appMain(String[] args) {
        int port = DEFAULT_PORT;
        boolean threaded = false;

        // Parse command-line arguments
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-t")) {
                // Enable virtual threading (Phase 3)
                threaded = true;
            } else if (args[i].equals("-q")) {
                // Quiet mode - suppress per-request logging
                quiet = true;
            } else if (args[i].equals("-k")) {
                // Enable keep-alive with optional timeout (Phase 2)
                keepAlive = true;
                if (i + 1 < args.length) {
                    try {
                        int timeout = Integer.parseInt(args[i + 1]);
                        keepAliveTimeout = timeout;
                        i++; // Consume the timeout argument
                    } catch (NumberFormatException e) {
                        // Next arg is not a number, use default timeout
                        keepAliveTimeout = DEFAULT_KEEPALIVE_TIMEOUT;
                    }
                }
            } else if (args[i].equals("-h") || args[i].equals("--help")) {
                // Show help and exit
                printUsage();
                System.exit(0);
            } else {
                // Any other numeric argument is treated as port number
                try {
                    port = Integer.parseInt(args[i]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid port number, using default: " + DEFAULT_PORT);
                }
            }
        }

        // Create server socket and enter main loop
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            // Display server configuration
            System.out.println("Server started on port " + port);
            System.out.println("Mode: " + (threaded ? "Virtual Threaded" : "Single Threaded"));
            System.out.println("Logging: " + (quiet ? "Quiet" : "Verbose"));
            System.out.println(
                    "Keep-Alive: " + (keepAlive ? "Enabled (timeout: " + keepAliveTimeout + "s)" : "Disabled"));
            System.out.println("Static files served from: " + PUBLIC_DIR);

            // Main server loop - accept and dispatch connections
            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();

                    if (threaded) {
                        // Phase 3: Handle connection in virtual thread
                        handleConnectionThreaded(clientSocket);
                    } else {
                        // Phase 1/2: Handle connection synchronously on main thread
                        handleConnection(clientSocket);
                        clientSocket.close();
                    }
                } catch (IOException e) {
                    System.err.println("Error accepting connection: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Could not start server: " + e.getMessage());
        }
    }

    private void printUsage() {
        System.out.println("Usage: java lts.java [options] [port]");
        System.out.println("Options:");
        System.out.println("  -t                Enable virtual threading");
        System.out.println("  -q                Quiet mode (disable per-request logging)");
        System.out.println("  -k [timeout]      Enable keep-alive (optional timeout in seconds, default: 5)");
        System.out.println("  -h, --help        Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java lts.java 8080           Start server on port 8080");
        System.out.println("  java lts.java -t 8080        Start with virtual threading");
        System.out.println("  java lts.java -k 8080        Start with keep-alive (5s timeout)");
        System.out.println("  java lts.java -k 30 8080     Start with keep-alive (30s timeout)");
        System.out.println("  java lts.java -t -k -q 8080  All options combined");
    }

    private void handleConnection(Socket socket) throws IOException {
        if (keepAlive) {
            handleWithKeepAlive(socket); // persistant connection
        } else {
            handleBasic(socket);
        }
    }

    private void handleBasic(Socket socket) throws IOException {

        long startTime = System.currentTimeMillis(); // socket connection start time

        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String requestLine = reader.readLine();

        if (requestLine == null) {
            return; // socket connection closed early
        }

        // Validate
        String[] requestParts = validateRequest(requestLine); // extracts method & path
        if (requestParts == null) {
            OutputStream out = socket.getOutputStream();
            sendError(out, 400, "Bad Request", false);
            return;
        }

        String method = requestParts[0];
        String path = requestParts[1];

        Map<String, String> headers = parseHeaders(reader);

        OutputStream out = socket.getOutputStream();

        if (!method.equals("GET")) {
            sendError(out, 405, "Method Not Allowed", false);
            return;
        }

        dispatchRequest(out, path, false); // pass false for shouldKeepAlive (basic mode doesn't persist)

        // Log request if not in quiet mode
        if (!quiet) {
            long duration = System.currentTimeMillis() - startTime;
            System.out.println(method + " " + path + " " + duration + "ms");
        } // connection closes automatically here
    } // end handleBasic

    private void handleWithKeepAlive(Socket socket) throws IOException {
        long startTime = System.currentTimeMillis();
        socket.setSoTimeout(keepAliveTimeout * 1000);

        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        OutputStream out = socket.getOutputStream();

        while (true) {
            String requestLine;
            try {
                requestLine = reader.readLine();
            } catch (SocketTimeoutException e) {
                return; // timeout
            }
            if (requestLine == null || requestLine.isEmpty()) {
                return; // socket closed because empty request line / null
            }
            // Parse and Validate Request
            String[] requestParts = validateRequest(requestLine);
            if (requestParts == null) {
                sendError(out, 400, "Bad Request", true);
                return;
            }

            String method = requestParts[0];
            String path = requestParts[1];

            Map<String, String> headers = parseHeaders(reader);

            if (!method.equals("GET")) {
                sendError(out, 405, "Method Not Allowed", true);
                return;
            }

            String connectionHeader = headers.get("connection");
            boolean clientWantsClose = (connectionHeader != null && connectionHeader.equalsIgnoreCase("close"));
            boolean shouldKeepAlive = !clientWantsClose;
            dispatchRequest(out, path, shouldKeepAlive);

            if (!quiet) {
                long now = System.currentTimeMillis();
                System.out.println(method + " " + path + " " + (now - startTime) + "ms");
            }

            if (!shouldKeepAlive) {
                return;
            }
        }
    } // end handleWithKeepAlive

    private void handleConnectionThreaded(Socket socket) {
        Thread.ofVirtual().start(() -> {
            try {
                handleConnection(socket);
            } catch (IOException e) {
                System.err.println("Error in virtual thread: " + e.getMessage());
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    System.err.println("Error closing socket: " + e.getMessage());
                }
            }
        });
    }

    private Map<String, String> parseHeaders(BufferedReader in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;

        // Read lines until we hit the blank line separating headers from body
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String headerName = line.substring(0, colonIndex).trim().toLowerCase();
                String headerValue = line.substring(colonIndex + 1).trim();
                headers.put(headerName, headerValue);
            }
        }

        return headers;
    }

    private String[] validateRequest(String requestLine) {
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            return null; // Malformed request line
        }
        return new String[] { parts[0], parts[1] };
    }

    private void dispatchRequest(OutputStream out, String path, boolean shouldKeepAlive) throws IOException {
        if (path.startsWith("/echo/")) {
            handleEcho(out, path, shouldKeepAlive);
        } else {
            handleStaticFile(out, path, shouldKeepAlive);
        }
    } // end dispatchRequest

    private void handleEcho(OutputStream out, String path, boolean shouldKeepAlive) throws IOException {
        String[] parts = path.split("/");
        if (parts.length < 3) {
            sendError(out, 400, "Missing Size argument", shouldKeepAlive);
            return;
        }
        int size;
        try {
            size = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            sendError(out, 400, "Invalid Size argument", shouldKeepAlive);
            return;
        }
        if (size < 0) {
            sendError(out, 400, "Negative Size argument", shouldKeepAlive);
            return;
        }

        byte[] payload = generatePayload(size);

        String hashString;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload);
            hashString = bytesToHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            sendError(out, 500, "SHA-256 Algorithm Not Found", shouldKeepAlive);
            return;
        }
        Map<String, String> extraHeaders = new HashMap<>();
        extraHeaders.put("X-Payload-Hash", hashString);
        sendResponse(out, 200, "OK", "text/plain", payload, extraHeaders, shouldKeepAlive);
    } // end handleEcho

    private void handleStaticFile(OutputStream out, String path, boolean shouldKeepAlive) throws IOException {
        // TODO: Implement static file serving with security checks
        if (path.equals("/")) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            sendError(out, 403, "Forbidden", shouldKeepAlive);
            return;
        }
        Path filePath = Paths.get(PUBLIC_DIR, path);
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            if (!tryServeCustom404(out, shouldKeepAlive)) {
                sendError(out, 404, "Not Found", shouldKeepAlive);
            }
            return;
        }
        byte[] content = Files.readAllBytes(filePath);
        String contentType = guessContentType(path);
        sendResponse(out, 200, "OK", contentType, content, null, shouldKeepAlive);
    }

    private boolean tryServeCustom404(OutputStream out, boolean shouldKeepAlive) throws IOException {

        Path custom404Path = Paths.get(PUBLIC_DIR, "404.html");
        if (Files.exists(custom404Path) && Files.isRegularFile(custom404Path)) {
            byte[] content = Files.readAllBytes(custom404Path);
            sendResponse(out, 404, "Not Found", "text/html", content, null, shouldKeepAlive);
            return true;
        }
        return false;
    }

    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // Response Utilities
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    private void sendResponse(OutputStream out, int code, String message, String contentType,
            byte[] body, Map<String, String> extraHeaders, boolean shouldKeepAlive)
            throws IOException {

        PrintWriter writer = new PrintWriter(out, false); // autoFlush = false

        // Status Line
        writer.print("HTTP/1.1 " + code + " " + message + "\r\n");

        // Headers
        writer.print("Content-Type: " + contentType + "\r\n");
        writer.print("Content-Length: " + body.length + "\r\n");
        writer.print("Date: " + new java.util.Date() + "\r\n");
        writer.print("Server: SimpleJava/1.0\r\n");

        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                writer.print(entry.getKey() + ": " + entry.getValue() + "\r\n");
            }
        }

        if (shouldKeepAlive) {
            writer.print("Connection: keep-alive\r\n");
            writer.print("Keep-Alive: timeout=" + keepAliveTimeout + "\r\n");
        } else {
            writer.print("Connection: close\r\n");
        }

        // Blank line separating headers from body
        writer.print("\r\n");
        writer.flush();

        // Body
        out.write(body);
        out.flush();
    }

    private void sendError(OutputStream out, int code, String message, boolean shouldKeepAlive)
            throws IOException {
        String htmlBody = "<html><body><h1>" + code + " " + message + "</h1></body></html>";
        byte[] bodyBytes = htmlBody.getBytes();
        sendResponse(out, code, message, "text/html", bodyBytes, null, shouldKeepAlive);
    }

    private String guessContentType(String path) {
        if (path.endsWith(".html") || path.endsWith(".htm")) {
            return "text/html";
        } else if (path.endsWith(".css")) {
            return "text/css";
        } else if (path.endsWith(".js")) {
            return "application/javascript";
        } else if (path.endsWith(".json")) {
            return "application/json";
        } else if (path.endsWith(".txt")) {
            return "text/plain";
        }
        return "application/octet-stream"; // Generic binary data
    }

    private byte[] generatePayload(int size) {
        byte[] payload = new byte[size];
        String pattern = "0123456789";
        byte[] patternBytes = pattern.getBytes();

        // Fill payload by repeating pattern
        for (int i = 0; i < size; i++) {
            payload[i] = patternBytes[i % patternBytes.length];
        }

        return payload;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

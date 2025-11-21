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

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // main
    //
    // Entry point - creates server instance and delegates to appMain.
    // Enables single-source execution via 'java lts.java'.
    //
    public static void main(String[] args) {
        new lts().appMain(args);
    }

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // appMain
    //
    // Configures and runs the HTTP server based on command-line arguments.
    // Parses options for threading mode, keep-alive, and logging, then
    // enters the main server loop to accept and dispatch connections.
    //
    // The server uses a strategy pattern to separate basic single-request
    // handling from keep-alive multi-request handling, allowing students
    // to implement phases incrementally without breaking prior work.
    //
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
            System.out.println("Keep-Alive: " + (keepAlive ? "Enabled (timeout: " + keepAliveTimeout + "s)" : "Disabled"));
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

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // printUsage
    //
    // Displays command-line help with available options and examples.
    //
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

    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // Request Handling Strategy
    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // handleConnection
    //
    // Dispatches to appropriate handler based on keep-alive mode.
    // Strategy pattern: basic handler for single requests, keep-alive
    // handler for persistent connections.
    //
    // TODO: Phase 1
    //   Check the keepAlive instance variable to determine which handler
    //   to call. This separates Phase 1 implementation (handleBasic) from
    //   Phase 2 implementation (handleWithKeepAlive), allowing you to test
    //   each phase independently.
    //
    private void handleConnection(Socket socket) throws IOException {
        // TODO: Implement dispatch logic
        if (keepAlive){
            handleWithKeepAlive(socket); // persistant connection
        }else {
            handleBasic(socket);
        }
    }

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // handleBasic
    //
    // Handles a single HTTP request and closes the connection.
    // Phase 1: Basic HTTP server implementation.
    //
    // This is the simpler handler used when keep-alive is disabled. It reads
    // one request, validates it, dispatches to the appropriate endpoint
    // handler, and returns. The connection is closed by the caller after
    // this method returns.
    //
    // TODO: Phase 1
    //   1. Create BufferedReader and OutputStream from socket streams .
    //   2. Read the request line with in.readLine() .
    //   3. Use parseHeaders() to read headers into a map .
    //   4. Use validateRequest() to extract method and path
    //   5. Check that method is "GET", send 405 error if not
    //   6. Use dispatchRequest() to route to appropriate handler
    //   7. Pass false for shouldKeepAlive (basic mode doesn't persist)
    //
    //   Hint: System.currentTimeMillis() can track request duration
    //   Hint: Check the 'quiet' flag before logging
    //
    private void handleBasic(Socket socket) throws IOException {
        long startTime = System.currentTimeMillis();
        // TODO: Implement basic request handling
        BufferedReader  reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String requestLine = reader.readLine();
        if (requestLine == null) return;
        
        // Validate
        String[] requestParts = validateRequest(requestLine); // extracts method & path
        if (requestParts == null) return;
        
        String method = requestParts[0];
        String path = requestParts[1]; 

        Map<String, String> headers = parseHeaders(reader);
        
        OutputStream out = socket.getOutputStream();

        if (!method.equals("GET")) {
            sendError(out, 405, "Method Not Allowed", false);
            return;
        }

        dispatchRequest(out, path, false);

        // Log request if not in quiet mode
        if (!quiet) {
            long duration = System.currentTimeMillis() - startTime;
            System.out.println(method + " " + path + " " + duration + "ms");
        }
        
    } // end handleBasic

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // handleWithKeepAlive
    //
    // Handles multiple HTTP requests on a single connection with timeout.
    // Phase 2: Keep-alive implementation.
    //
    // This handler implements HTTP/1.1 persistent connections. It loops to
    // process multiple requests on the same TCP connection, reducing the
    // overhead of connection setup/teardown. The loop exits when:
    //   - Client sends "Connection: close" header
    //   - Socket timeout expires (no request within keepAliveTimeout seconds)
    //   - Client closes connection (readLine returns null)
    //   - An error occurs
    //
    // TODO: Phase 2
    //   1. Set socket timeout using socket.setSoTimeout(keepAliveTimeout * 1000)
    //   2. Create BufferedReader and OutputStream from socket streams
    //   3. Enter infinite loop for handling multiple requests
    //   4. Wrap in.readLine() in try-catch to handle SocketTimeoutException
    //   5. Break loop if timeout, null, or empty request line
    //   6. Parse headers and validate request (similar to handleBasic)
    //   7. Check headers.get("connection") to see if client sent "close"
    //   8. Calculate shouldKeepAlive = !clientWantsClose
    //   9. Pass shouldKeepAlive to dispatchRequest (tells response handler)
    //   10. Break loop if shouldKeepAlive is false
    //
    //   Key difference from handleBasic: The LOOP and Connection header negotiation
    //
    //   Hint: Use headers.getOrDefault("connection", "") to safely get header
    //   Hint: Use .equalsIgnoreCase("close") for case-insensitive comparison
    //
    private void handleWithKeepAlive(Socket socket) throws IOException {
        // TODO: Implement keep-alive request handling with loop
    }

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // handleConnectionThreaded
    //
    // Handles a connection in a new virtual thread.
    // Phase 3: Concurrent connection handling.
    //
    // This method spawns a virtual thread to handle the connection,
    // allowing the server to process multiple requests concurrently.
    // Virtual threads are lightweight and can scale to thousands of
    // concurrent connections without the overhead of platform threads.
    //
    // TODO: Phase 3
    //   1. Use Thread.ofVirtual().start() to create and start a virtual thread
    //   2. Inside the lambda:
    //      - Wrap handleConnection(socket) in try-catch for IOException
    //      - Add finally block to ensure socket.close() always happens
    //      - Catch and log any errors during close as well
    //   3. Log errors to System.err with descriptive messages
    //
    //   Pattern:
    //     Thread.ofVirtual().start(() -> {
    //         try {
    //             // handle connection
    //         } catch (IOException e) {
    //             // log error
    //         } finally {
    //             // close socket with its own try-catch
    //         }
    //     });
    //
    //   Key insight: The method returns immediately after spawning the thread,
    //   allowing the main loop to accept the next connection while this one
    //   is processed concurrently.
    //
    private void handleConnectionThreaded(Socket socket) {
        // TODO: Implement virtual thread connection handling
    }

    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // Request Processing Utilities
    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // parseHeaders
    //
    // Reads HTTP headers from input stream and returns them as a map.
    // Header names are normalized to lowercase for case-insensitive lookup.
    //
    // HTTP headers follow the format "Header-Name: value" and are terminated
    // by a blank line. This method reads until it encounters the blank line,
    // building a map of header name -> value pairs.
    //
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

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // validateRequest
    //
    // Validates request line format and returns [method, path] array.
    // Returns null if request is malformed.
    //
    // Expected format: "METHOD /path HTTP/version"
    // Example: "GET /index.html HTTP/1.1"
    //
    // We extract only method and path, ignoring the HTTP version since we
    // always respond with HTTP/1.1 regardless of what the client sends.
    //
    private String[] validateRequest(String requestLine) {
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            return null; // Malformed request line
        }
        return new String[] { parts[0], parts[1] };
    }

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // dispatchRequest
    //
    // Routes request to appropriate handler based on path.
    //
    // Routing rules:
    //   /echo/{size} -> handleEcho (dynamic payload generation)
    //   Everything else -> handleStaticFile (serve from public/ directory)
    //
    // TODO: Phase 1
    //   Check if path starts with "/echo/" and route accordingly.
    //   For Phase 1, you only need to route to handleStaticFile.
    //   Phase 3 will add handleEcho implementation.
    //
    //   Hint: Use path.startsWith() to check the prefix
    //
    private void dispatchRequest(OutputStream out, String path, boolean shouldKeepAlive) throws IOException {
        // TODO: Implement routing logic
        // phase 1 -> route to handlestaticfile
        if (path.startsWith("/echo/")) {
            handleEcho(out, path, shouldKeepAlive);
        }else{
            handleStaticFile(out, path, shouldKeepAlive);
        }
    }

    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // Endpoint Handlers
    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // handleEcho
    //
    // Handles /echo/{size} endpoint - generates deterministic payload of
    // specified size and returns it with SHA-256 hash verification header.
    //
    // This endpoint is used for load testing and verification. It generates
    // a repeating pattern of bytes ("0123456789"...) of the requested size
    // and computes a SHA-256 hash that the client can verify. This ensures
    // data integrity during transmission.
    //
    // Example: GET /echo/100 returns 100 bytes with X-Payload-Hash header
    //
    // TODO: Phase 3
    //   1. Split path by "/" and extract size from parts[2]
    //   2. Validate that parts.length >= 3, send 400 error if not
    //   3. Parse size as integer with Integer.parseInt(), send 400 if invalid
    //   4. Check size >= 0, send 400 if negative
    //   5. Call generatePayload(size) to create byte array
    //   6. Compute SHA-256 hash:
    //      - MessageDigest.getInstance("SHA-256")
    //      - digest.digest(payload) returns hash bytes
    //      - Use bytesToHex() to convert to hex string
    //   7. Create HashMap for extra headers, put "X-Payload-Hash" -> hash
    //   8. Call sendResponse with 200, "OK", "text/plain", payload, extraHeaders, shouldKeepAlive
    //
    //   Hint: Wrap MessageDigest in try-catch, send 500 error on exception
    //   Hint: Use sendError() for all error responses
    //
    private void handleEcho(OutputStream out, String path, boolean shouldKeepAlive) throws IOException {
        // TODO: Implement echo endpoint with hash generation
    }

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // handleStaticFile
    //
    // Serves static files from the public directory. Handles default
    // index.html, directory traversal protection, and custom 404 pages.
    //
    // Security considerations:
    //   - Rejects paths containing ".." to prevent directory traversal
    //   - Only serves regular files (not directories or special files)
    //   - All paths are relative to PUBLIC_DIR
    //
    // Special cases:
    //   - "/" is mapped to "/index.html"
    //   - Missing files trigger custom 404.html if it exists
    //
    // TODO: Phase 1
    //   1. Check if path equals "/" and map it to "/index.html"
    //   2. SECURITY: Check if path contains ".." and send 403 error if so
    //   3. Build full path: Paths.get(PUBLIC_DIR, path)
    //   4. Check if file exists AND is regular file with Files.exists() and Files.isRegularFile()
    //   5. If file not found:
    //      - Try tryServeCustom404() (Phase 2 feature, just call it)
    //      - If that returns false, sendError 404
    //   6. If file exists:
    //      - Read content with Files.readAllBytes()
    //      - Get MIME type with guessContentType()
    //      - Call sendResponse with 200, "OK", contentType, content, null, shouldKeepAlive
    //
    //   Hint: Use path.contains("..") for security check
    //   Hint: tryServeCustom404 returns true if it handled the 404
    //
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

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // tryServeCustom404
    //
    // Attempts to serve custom 404.html page if it exists.
    // Returns true if custom 404 was served, false otherwise.
    //
    // This provides a better user experience than the default error page,
    // allowing site-specific branding and helpful navigation on 404 errors.
    //
    // TODO: Phase 2
    //   1. Build path to 404.html: Paths.get(PUBLIC_DIR, "404.html")
    //   2. Check if it exists AND is a regular file
    //   3. If it doesn't exist, return false
    //   4. If it exists:
    //      - Read content with Files.readAllBytes()
    //      - Call sendResponse with 404, "Not Found", "text/html", content, null, shouldKeepAlive
    //      - Return true
    //
    //   Note: Still send 404 status code, just with custom HTML content
    //
    private boolean tryServeCustom404(OutputStream out, boolean shouldKeepAlive) throws IOException {
        // TODO: Implement custom 404 page serving
        return false; // Placeholder - replace with actual implementation
    }

    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // Response Utilities
    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // sendResponse
    //
    // Unified response writer - sends HTTP response with status, headers,
    // and body. Handles optional extra headers and connection persistence.
    //
    // All responses go through this method, ensuring consistent formatting.
    // HTTP response format:
    //   Status line: "HTTP/1.1 {code} {message}\r\n"
    //   Headers: "Header-Name: value\r\n" ...
    //   Blank line: "\r\n"
    //   Body: raw bytes
    //
    // The Connection header is set based on shouldKeepAlive to inform the
    // client whether the connection will persist after this response.
    //
    // TODO: Phase 1
    //   1. Create PrintWriter from OutputStream (set autoFlush to false)
    //   2. Write status line: "HTTP/1.1 {code} {message}\r\n"
    //   3. Write Content-Type header: "Content-Type: {contentType}\r\n"
    //   4. Write Content-Length header: "Content-Length: {body.length}\r\n"
    //   5. If extraHeaders is not null, iterate and write each header
    //   6. Write Connection header: "Connection: keep-alive" or "close" based on shouldKeepAlive
    //   7. Write blank line: "\r\n" (separates headers from body)
    //   8. Flush the PrintWriter
    //   9. Write body bytes directly to OutputStream with out.write(body)
    //   10. Flush the OutputStream
    //
    //   CRITICAL: Use "\r\n" (CRLF) for line endings, not "\n"
    //   CRITICAL: Don't forget the blank line between headers and body
    //
    //   Hint: Use writer.print() not writer.println() to control line endings
    //   Hint: Ternary operator for Connection: (shouldKeepAlive ? "keep-alive" : "close")
    //
    private void sendResponse(OutputStream out, int code, String message, String contentType,
                             byte[] body, Map<String, String> extraHeaders, boolean shouldKeepAlive)
                             throws IOException {
                                
        PrintWriter writer = new PrintWriter(out, false); // autoFlush = false
        
        // Status Line
        writer.print("HTTP/1.1 " + code + " " + message + "\r\n");
        
        // Headers
        writer.print("Content-Type: " + contentType + "\r\n");
        writer.print("Content-Length: " + body.length + "\r\n");
        
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                writer.print(entry.getKey() + ": " + entry.getValue() + "\r\n");
            }
        }
        
        writer.print("Connection: " + (shouldKeepAlive ? "keep-alive" : "close") + "\r\n");
        
        // Blank line separating headers from body
        writer.print("\r\n");
        writer.flush();
        
        // Body
        out.write(body);
        out.flush();
    }

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // sendError
    //
    // Sends an HTTP error response with a simple HTML body.
    //
    // Convenience wrapper around sendResponse for error cases. Generates a
    // minimal HTML page displaying the error code and message.
    //
    // TODO: Phase 1
    //   1. Create HTML body string: "<html><body><h1>{code} {message}</h1></body></html>"
    //   2. Call sendResponse with:
    //      - code and message parameters
    //      - "text/html" as content type
    //      - body.getBytes() to convert string to bytes
    //      - null for extraHeaders (no custom headers needed)
    //      - shouldKeepAlive parameter
    //
    //   This is just a simple wrapper - most work is done by sendResponse
    //
    private void sendError(OutputStream out, int code, String message, boolean shouldKeepAlive)
                          throws IOException {
        String htmlBody = "<html><body><h1>" + code + " " + message + "</h1></body></html>";
        byte[] bodyBytes = htmlBody.getBytes();
        sendResponse(out, code, message, "text/html", bodyBytes, null, shouldKeepAlive);
    }

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // guessContentType
    //
    // Maps file extensions to MIME types for Content-Type header.
    //
    // Proper Content-Type headers help browsers render files correctly.
    // This is a simplified version - production servers use more extensive
    // MIME type databases.
    //
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

    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // Data Generation & Hashing
    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // generatePayload
    //
    // Generates deterministic byte payload of specified size using a
    // repeating pattern. Same size always produces same payload.
    //
    // The pattern "0123456789" repeats to fill the requested size. This
    // deterministic approach allows clients to verify they received the
    // correct data by computing the hash of the expected pattern.
    //
    // Example: size=25 produces "0123456789012345678901234"
    //
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

    //''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''
    // bytesToHex
    //
    // Converts byte array to hexadecimal string representation.
    //
    // Used to convert SHA-256 hash bytes to a readable hex string for the
    // X-Payload-Hash header. Each byte becomes two hex digits (00-ff).
    //
    // Example: [0x1a, 0x2b, 0x3c] -> "1a2b3c"
    //
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

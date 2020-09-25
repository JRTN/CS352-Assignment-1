import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;

final public class PartialHTTP1Server {

    public static void main(final String[] args) {
        /*
            Error: No arguments
            Resolution: Print usage and end
        */
        if (args.length < 1) {
            System.out.println("Usage: java PartialHTTP1Server <port name>");
            return;
        }
        int PORT_NUMBER;
        try {
            PORT_NUMBER = Integer.parseInt(args[0]);

        /*
            Error: Invalid argument
            Resolution: Inform user and end
        */
        } catch (final NumberFormatException e) {
            System.out.println("ERROR: Failed to parse argument to integer.");
            return;
        }

        ServerSocket SERVER_SOCKET;
        try {
            SERVER_SOCKET = new ServerSocket(PORT_NUMBER);

        /*
            Error: Failed to create server socket.
            Resolution: Inform user and end.
        */
        } catch (final Exception e) {
            System.out.printf("ERROR: Failed to create server socket on port %d.%n%s%n", PORT_NUMBER, e.getMessage());
            return;
        }

        /*
            Create the thread pool. Five arguments
                1. Core Pool Size - We want to keep 5 threads alive when idle
                2. Max Pool Size - We want a maximum of 50 threads running at any time
                3. Time to keep alive - Threads will begin to be killed off after this amount of time of being idle.
                4. Unit of time for #3 - Milliseconds or seconds, don't matter
                5. The Work Queue - We just need any blocking queue so we'll use a linked queue
        */
        ExecutorService threadPoolService = new ThreadPoolExecutor(5, 50, 1000, TimeUnit.MILLISECONDS, new SynchronousQueue<>()) {
            int currentThreads = 0;

            @Override
            public void execute(Runnable r) throws RejectedExecutionException {
                synchronized (this) {
                    if(currentThreads >= getMaximumPoolSize()) {
                        throw new RejectedExecutionException("Maximum thread count reached.");
                    }

                    currentThreads++;
                }
                super.execute(r);
            }

            @Override
            protected void afterExecute(Runnable r, Throwable t) {
                synchronized (this) {
                    currentThreads--;
                }
                super.afterExecute(r, t);
            }

        };

        //Server loop
        //noinspection InfiniteLoopStatement
        while (true) {
            Socket SOCKET;
            try {
                SOCKET = SERVER_SOCKET.accept();

            /*
                Error: Failed to accept connection on the socket.
                Resolution: Inform the user and move on.
            */
            } catch (final Exception e) {
                System.out.printf("ERROR: Failed to accept connection on socket.%n%s", e.getMessage());
                continue;
            }

            try {
                threadPoolService.execute(new ConnectionHandler(SOCKET));

            /*
                Error: Thread Pool rejected a new command -indicates maximum connections reached
                Resolution: Write 503 to socket
            */
            } catch (final RejectedExecutionException e) {
                try {
                    //Write error 503 to the socket's output stream
                    BufferedWriter OUT = new BufferedWriter(new OutputStreamWriter(SOCKET.getOutputStream()));
                    System.out.println("Sending 503 response");
                    OUT.write(String.format("HTTP/1.0 %s\r\n", Types.StatusCode.SERVICE_UNAVAILABLE));
                    //Clean up connections
                    OUT.flush();
                    OUT.close();
                    SOCKET.close();

                    /*
                        Error: Failed to write to socket and clean up
                        Resolution: Inform user and move on
                    */
                } catch (final IOException ex) {
                    System.out.printf("ERROR: Failed to write 503 response from main.%n%s", ex.getMessage());
                } catch (final Exception ex) {
                    System.out.printf("ERROR: Unspecified exception.%n%s", ex.getMessage());
                }
            /*
                Error: Failed to create Client Handler for a connection
                Resolution: Inform the user and move on
            */
            } catch (final InstantiationException e) {
                System.out.printf("ERROR: Failed to create thread for socket.%n%s", e.getMessage());
            }
        }
    }
}

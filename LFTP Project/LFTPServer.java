import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.zip.CRC32;

public class LFTPServer {
    public int DATA_LEN = 1024;
    public static int PORT = 30000;  // Public port.

    InetAddress address;
    String path;
	
	// LFTPServer Constructor.
	public LFTPServer(InetAddress address, String path) {
        this.address = address;
        this.path = path;
    }

    // Method for receiving request.
	public void sendingReceive(int _toPort, int _rePort, String clientAddress) {
		System.out.println("> Ready to receive File from LFTP Client");
		SendingRequest sendingRequest = new SendingRequest(_toPort, _rePort, clientAddress);
		sendingRequest.start();
	}
	
    public class SendingRequest extends Thread {
        DatagramSocket receiveSocket;
        DatagramSocket toSocket;
		
		int prevSeqNum = -1;			
		int expectedNum = 0;				
        boolean isCompleted = false;	

        public int rePort;
        public int toPort;
        
        String clientAddress;
        
        public SendingRequest(int _toPort, int _rePort, String _clientAddress) {
            toPort = _toPort;
            rePort = _rePort;
            clientAddress = _clientAddress;
        }

        public void run() {
            try {
                // Create two sockets, one for receiving file, the others for sending ack messages.
                receiveSocket = new DatagramSocket(rePort);	
                toSocket = new DatagramSocket();				
    
                try {
                    byte[] buffer = new byte[DATA_LEN];									
                    DatagramPacket packetIn = new DatagramPacket(buffer, buffer.length);	
                    // address = InetAddress.getByName("127.0.0.1");
                    
                    FileOutputStream fileStream = null;
                    
                    path = ((path.substring(path.length()-1)).equals("/"))? path: path + "/";	
                    File filePath = new File(path);
                    if (!filePath.exists()) filePath.mkdir();
                    
                    
                    while (!isCompleted) {
                        // Receive packet.
                        receiveSocket.receive(packetIn);
    
                        byte[] checkSumReceived = getRangeArray(buffer, 0, 8);
                        CRC32 checkSum = new CRC32();
                        checkSum.update(getRangeArray(buffer, 8, packetIn.getLength()));
                        byte[] checkSumCal = ByteBuffer.allocate(8).putLong(checkSum.getValue()).array();
                        
                        // if packet is not corrupted
                        if (Arrays.equals(checkSumReceived, checkSumCal)){
                            int seqNum = ByteBuffer.wrap(getRangeArray(buffer, 8, 12)).getInt();
                            System.out.println("> Receive sequence number: " + seqNum + " from: " + clientAddress);
                            
                            // If packet received in order.
                            if (seqNum == expectedNum){
                                if (packetIn.getLength() == 12){
                                    byte[] ackPkt = makePacket(-2);	
                                    for (int i = 0; i < 20; i++) {
                                        toSocket.send(new DatagramPacket(ackPkt, ackPkt.length, InetAddress.getByName(clientAddress), toPort));
                                    }
                                    isCompleted = true;
                                    System.out.println("> File transfer complete!");
                                    continue;
                                }
                                
                                // else send ack
                                else {
                                    sendAckNum(toSocket, toPort, seqNum, clientAddress);
                                }
                                
                                // First packet to transfer.
                                if (seqNum == 0 && prevSeqNum == -1){
                                    int fileNameLength = ByteBuffer.wrap(getRangeArray(buffer, 12, 16)).getInt();
                                    String fileName = new String(getRangeArray(buffer, 16, 16 + fileNameLength));
                                    System.out.println("> The fileName's length: " + fileNameLength);
                                    System.out.println("> The fileName is " + fileName);
                                    
                                    // create file
                                    File file = new File(path + fileName);
                                    if (!file.exists()) 
                                        file.createNewFile();
                                
                                    // Write data to the file.
                                    fileStream = new FileOutputStream(file);
                                    fileStream.write(buffer, 16 + fileNameLength, packetIn.getLength() - 16 - fileNameLength);
                                }
                                
                                
                                // else if not first packet write to FileOutputStream
                                else fileStream.write(buffer, 12, packetIn.getLength() - 12);
                                
                                expectedNum ++; 			
                                prevSeqNum = seqNum;	
                            }
                            
                            // packet is not received in order, send duplicate message.
                            else sendDuplicateMessage(toSocket, toPort, prevSeqNum, clientAddress);
                            
                        }
                        
                        // else packet is corrupted
                        else sendCorruptMessage(toSocket, toPort, prevSeqNum, clientAddress);
                        
                    }
                    if (fileStream != null) fileStream.close();
                } catch (Exception e) {
                    e.printStackTrace();
                    System.exit(-1);
                } finally {
                    receiveSocket.close();
                    toSocket.close();
                }
            } catch (SocketException e1) {
                e1.printStackTrace();
            }            
        }
		

    }

    public void gettingReceive(int toPort, int rePort, String address, String fileName) {
        System.out.println("> Ready to send File to LFTP Client");
        GettingRequest gettingRequest = new GettingRequest(toPort, rePort, address, fileName);
        gettingRequest.start();
    }

    public class GettingRequest extends Thread {
        public int DATA_LEN = 1012;
        public int windowSize = 10;
        public int TIME = 300;
        public boolean START = true;
        public boolean STOP  = false;
        InetAddress address;

        // base and next sequence number of window size.
        int base;
        int nextSeqNum;

        // pair to store the file's name and file's length.
        String fileName;

        // Timer.
        Timer timer;

        // Semaphore, keep the base and nextSeqNum sync.
        Semaphore semaphore;

        // Vector of packets, store all the packets.
        Vector<byte[]> packets;

        // Flag, look whether the transfer is completed.
        boolean isCompleted;
        
        DatagramSocket toSocket,receiveSocket;

        public GettingRequest(int toPort, int rePort, String address, String fileName) {
            try {
            	base = 0;
            	nextSeqNum = 0;
            	packets = new Vector<byte[]>(windowSize);
            	isCompleted = false;
            	semaphore = new Semaphore(1);
            	this.fileName = fileName;
            	this.address = InetAddress.getByName(address);
                toSocket = new DatagramSocket();
                receiveSocket = new DatagramSocket(rePort);
                
                RDT_RCV rdt_rcv = new RDT_RCV(receiveSocket);
                RDT_SEND rdt_send = new RDT_SEND(toSocket, toPort, rePort);
                rdt_rcv.start();
                rdt_send.start();
                
                
                //rdt_send.join();
                //timer.cancel();
                //rdt_rcv.interrupt();
                
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(-1);
            }              
        }

        // RDT_RCV
        public class RDT_RCV extends Thread {
        	private DatagramSocket socket;
        	
        	// Extract packet and decode.
        	private int extractPacket(byte[] packet) {
        		// checksum + ACK.
        		byte[] checkSumArray = getRangeArray(packet, 0, 8);
        		byte[] ackNumArray = getRangeArray(packet, 8, 12);
        		CRC32 checkSum = new CRC32();
        		checkSum.update(ackNumArray);
        		byte[] checkSumCheckArray = ByteBuffer.allocate(8).putLong(checkSum.getValue()).array();
        		if (Arrays.equals(checkSumArray, checkSumCheckArray)) return ByteBuffer.wrap(ackNumArray).getInt();
        		else return -1;
        	}
        	
        	
        	public void run() {
        		try {
        			byte[] ackData = new byte[12];
        			DatagramPacket packetIn = new DatagramPacket(ackData, ackData.length);
        			try {
        				while (!isCompleted) {
        					socket.receive(packetIn);
        					int ackNum = extractPacket(ackData);
        					System.out.println("> Received Ack " + ackNum);
        					
        					// Situation 1 : If ACK is not corrupted.
        					if (ackNum != -1) {
        						// Duplicate ack.
        						if (base == ackNum+1) {
        							semaphore.acquire();
        							setTimer(STOP);
        							nextSeqNum = base;
        							semaphore.release();
        						}
        						// Teardown
        						else if (ackNum == -2) {
        							isCompleted = true;
        						}
        						// Normal
        						else {
        							base = ackNum+1;
        							semaphore.acquire();
        							if (base == nextSeqNum)
        								setTimer(STOP);
        							else
        								setTimer(START);
        							semaphore.release();
        						}
        					}
        				}
        					// DO NOTHING
        			} catch (Exception e) {
        				e.printStackTrace();
        			} finally {
        				socket.close();
        			}
        		} catch (Exception e) {
        			e.printStackTrace();
        			System.exit(-1);
        		}
        	}
        	
        	public RDT_RCV(DatagramSocket socket) {
        		this.socket = socket;
        	}
        }
        
        // RDT_SEND
        public class RDT_SEND extends Thread {
        	private DatagramSocket socket;
        	private int toPort;
        	public RDT_SEND(DatagramSocket _socket, int _toPort, int _rePort) {
        		socket = _socket;
        		toPort = _toPort;
        	}
        	
        	// Method for making packets.
        	public void run() {
        		try {
        			String filePath = System.getProperty("user.dir");
        			filePath = filePath + "\\" + fileName;
    				FileInputStream fileStream = new FileInputStream(new File(filePath));
    				int previous = -1;
    				
    				try {
    					while (!isCompleted) {
    						// WindowSize is not full.
    						if (nextSeqNum < base + windowSize) {
    							semaphore.acquire();
    							if (base == nextSeqNum) {
    								setTimer(START);
    							}
    							byte[] outputData = new byte[10];
    							boolean isFinalSeqNum = false;
    							
    							// If packet is in packets.
    							if (nextSeqNum < packets.size()) {
    								outputData = packets.get(nextSeqNum);
    							}
    							// Else make packet.
    							else {
    								// If it's the first packet.
    								if (nextSeqNum == 0) {
    									byte[] fileNameArray = fileName.getBytes();
    									byte[] fileNameLengthBytes = ByteBuffer.allocate(4).putInt(fileNameArray.length).array();
    									byte[] buffer = new byte[DATA_LEN];
    									int dataLength = fileStream.read(buffer, 0, DATA_LEN - 4 - fileNameArray.length);
    									byte[] dataBytes = getRangeArray(buffer, 0, dataLength);
    									ByteBuffer packetData = ByteBuffer.allocate(4 + fileNameArray.length + dataBytes.length);
    									packetData.put(fileNameLengthBytes);	
    									packetData.put(fileNameArray);			
    									packetData.put(dataBytes);				
    									outputData = makePacket(nextSeqNum, packetData.array());		
    									previous = nextSeqNum;
    								}
    								else {
    									byte[] buffer = new byte[DATA_LEN];
    									int readDataLength = fileStream.read(buffer, 0, DATA_LEN);
    									if (readDataLength == -1) {
    										isFinalSeqNum = true;
    										outputData = makePacket(nextSeqNum, new byte[0]);
    									}
    									else {
    										byte[] data = getRangeArray(buffer, 0, readDataLength);
    										outputData = makePacket(nextSeqNum, data);
    									}
    									previous = nextSeqNum;
    								}
    								packets.add(outputData);
    							}
    							DatagramPacket packet = new DatagramPacket(outputData, outputData.length, address, toPort);
    							socket.send(packet);
    							System.out.println("> Send sequence number: " + nextSeqNum);
    							
    							if(!isFinalSeqNum)
    								nextSeqNum++;
    							//if (previous == nextSeqNum) {
    								//System.out.println("File Transfer completed!");
    								//isCompleted = true;
    							//}
    								
    							semaphore.release();
    						}
    						sleep(5);
    					}
    				} catch (Exception e) {
    					e.printStackTrace();
    				} finally {
    					setTimer(STOP);
    					socket.close();
    					fileStream.close();
    				}
    			} catch (Exception e) {
    				// TODO Auto-generated catch block
    				e.printStackTrace();
    				System.exit(-1);
    			}
        	}
        }
        
    	public byte[] makePacket(int seqNum, byte[] dataArray) {
    		byte[] seqNumArray = ByteBuffer.allocate(4).putInt(seqNum).array();
    		
    		// Make checkSum.
    		CRC32 checkSum = new CRC32();
    		checkSum.update(seqNumArray);
    		checkSum.update(dataArray);
    		byte[] checkSumArray = ByteBuffer.allocate(8).putLong(checkSum.getValue()).array();
    		
    		
    		// Make packet.
    		ByteBuffer sndpkt = ByteBuffer.allocate(8 + 4 + dataArray.length);
    		sndpkt.put(checkSumArray);
    		sndpkt.put(seqNumArray);
    		sndpkt.put(dataArray);
    		return sndpkt.array();
    	}
        
        // Method, start or stop the timer.
        public void setTimer(boolean ACTION) {
            // FALSE : Just stop the timer.
            if (timer != null) {
                timer.cancel();
            }
            // START : Start a new timer.
            if (ACTION) {
                timer = new Timer();
                timer.schedule(new TimeoutTask(), TIME);
            }
        }

        // TimeTask when timeout occurs.
        public class TimeoutTask extends TimerTask {
            public void run() {
                try {
                    semaphore.acquire();
                    System.out.println("> Sending Timeout!");
                    nextSeqNum = base;
                    semaphore.release();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    // Method for sending ack.
    public void sendAckNum(DatagramSocket toSocket, int toPort, int seqNum, String clientAddress) throws IOException {
        byte[] ackPkt = makePacket(seqNum);
        toSocket.send(new DatagramPacket(ackPkt, ackPkt.length, InetAddress.getByName(clientAddress), toPort));
        //toSocket.send(new DatagramPacket(ackPkt, ackPkt.length, address, toPort));
        System.out.println("> Send Ack " + seqNum + " to " + clientAddress);
    }

    // Method For sending duplicate ack.
    public void sendDuplicateMessage(DatagramSocket toSocket, int toPort, int prevSeqNum, String clientAddress) throws IOException {
        byte[] ackPkt = makePacket(prevSeqNum);
        toSocket.send(new DatagramPacket(ackPkt, ackPkt.length, InetAddress.getByName(clientAddress), toPort));
        System.out.println("> Send duplicate Ack " + prevSeqNum + " to " + clientAddress);
    }
    
    // Method For sending corrupt message.
    public void sendCorruptMessage(DatagramSocket toSocket, int toPort, int prevSeqNum, String clientAddress) throws IOException {
        System.out.println("> Packet Corrupt!");
        byte[] ackPkt = makePacket(prevSeqNum);
        toSocket.send(new DatagramPacket(ackPkt, ackPkt.length, InetAddress.getByName(clientAddress), toPort));
        System.out.println("> Sent Duplicate Ack " + prevSeqNum + " to " + clientAddress);
    }
    
    
	// Make packet consis of ack message.
	public byte[] makePacket (int ackNum) {
		byte[] ackNumArray = ByteBuffer.allocate(4).putInt(ackNum).array();
		// calculate checksum
		CRC32 checkSum = new CRC32();
        checkSum.update(ackNumArray);
        
		// construct Ack packet
		ByteBuffer sndpkt = ByteBuffer.allocate(12);
		sndpkt.put(ByteBuffer.allocate(8).putLong(checkSum.getValue()).array());
		sndpkt.put(ackNumArray);
		return sndpkt.array();
	}
	
    // Method for copy a specific range of an Array.
    public byte[] getRangeArray(byte[] sourceArray, int start, int end) {
        int length = 0;
        // Prevent the index out of boundary.
        if (end > sourceArray.length)
            length = sourceArray.length - start;
        else
            length = end - start;
        byte[] targetArray = new byte[length];
        // System.out.println(length);
        System.arraycopy(sourceArray, start, targetArray, 0, length);
        return targetArray;
    }
	
	// main function
	public static void main(String[] args) throws IOException {
        // Use to receive commands.
        DatagramSocket socket = new DatagramSocket(PORT);
        byte[] buffer = new byte[1024];

        // Initial port number.
        int newToPort = 29999; // 30000
        int newRePort = 30000; // 30001

        // Default path is where the server.java is in.
        InetAddress localAddress = InetAddress.getLocalHost();
        LFTPServer server = new LFTPServer(localAddress, "./");
        
        // Current time.
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String current = df.format(System.currentTimeMillis());
        
        System.out.println("> " + current);
        System.out.println("> Welcome using LFTP Server");
        System.out.println("> LFTP Server Address: " + localAddress.toString().split("/")[1]);
        System.out.println("> Please input specific process commands in your LFTP Client");
        System.out.println("> Email liangjh45@mail2.sysu.edu.cn if you have any problem");
        
        // Server process.
        while (true) {
            // Transfer new empty port for new client.
            newToPort += 2;
            newRePort = newToPort+1;

            // Receive commands from New Clients.
            DatagramPacket cmdPkt = new DatagramPacket(buffer, buffer.length);
            socket.receive(cmdPkt);
            String commands = new String(cmdPkt.getData(), 0, cmdPkt.getLength());
            String[] Tokens = commands.split(" ");
            System.out.println("> Receive " + Tokens[1] + " command from " + Tokens[1]);

            // Send new empty port message for clients.
            String portList = newToPort + "::" + newRePort + "::";
            DatagramPacket portPkt = new DatagramPacket(portList.getBytes(), portList.getBytes().length, cmdPkt.getAddress(), cmdPkt.getPort());
            socket.send(portPkt);

            // Process specific commands.
            if (Tokens[1].equals("lsend")) {
                server.sendingReceive(newToPort, newRePort, cmdPkt.getAddress().toString().substring(1));
            }
            else if (Tokens[1].equals("lget")) {
            	server.gettingReceive(newToPort, newRePort, cmdPkt.getAddress().toString().substring(1), Tokens[3]);
            }
            else if (Tokens[1].equals("quit")) {
                System.out.println("> LFTP Server exit!");
                break;
            }
            else {
                System.out.println("> Invalid commands");
            }
        }

        socket.close();
	}
}
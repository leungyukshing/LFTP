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
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.zip.CRC32;


// LFTP Client is implemented by UDP.
// LFTP can download and upload a file from server.
public class LFTPClient {
    // Some constant number.
    // DATA_LEN   : The size of packets.
    // windowSize : The size of windows.
    // TIME       : The time of timeout.
    // START      : (TRUE) Flag to start the timer.
    // STOP       : (FALSE) Flag to stop the timer.
    public static final int DATA_LEN = 1012;
    public static final int REC_LEN = 1024;
    public static final int windowSize = 10;
    public static final int TIME = 300;
    public static final boolean START = true;
    public static final boolean STOP  = false;
    InetAddress address;
    int threshold;

    // base and next sequence number of window size.
    int base;
    int nextSeqNum;

    // pair to store the file's name and file's length.
    String filePath;
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

    public LFTPClient(int _toPort, int _rcPort, String _filePath, String _fileName, String _address) {
    	base = 0;
    	nextSeqNum = 0;
        threshold = windowSize;
    	filePath = _filePath;
    	fileName = _fileName;
    	packets = new Vector<byte[]>(windowSize);
    	isCompleted = false;
    	semaphore = new Semaphore(1);
    	System.out.println("> FilePath=" + _filePath);
    	
		try {
			address = InetAddress.getByName(_address);
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }
    
    // Method for upload file.
    public void UploadFile(int _toPort, int _rcPort) {
    	try {
    		System.out.println("> Begin sending " + fileName + " to " + address);
    		toSocket = new DatagramSocket();
    		// receiveSocket = new DatagramSocket(_rcPort);
    		
    		RDT_RCV rdt_rcv = new RDT_RCV(_rcPort);
    		RDT_SEND rdt_send = new RDT_SEND(toSocket, _toPort, _rcPort);
    		rdt_rcv.start();
    		rdt_send.start();
    		
    		rdt_send.join();
    		System.exit(-1);
    		
    	} catch (Exception e) {
    		e.printStackTrace();
    		System.exit(-1);
    	}   	
    }
    
    // Method for download file.
    
    
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
    
    // Thread: RDT_RCV.
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
                int[] ackCount = new int[102400];
    			try {
    				while (!isCompleted) {
    					socket.receive(packetIn);
    					int ackNum = extractPacket(ackData);
                        ackCount[ackNum]++;
                        for (int i = 0; i < 102400; i++) {
                            if (ackCount[i] >= 3) {
                                // 3 Duplicate ACKs
                                System.out.println("> Fast Recovery!");
                                threshold /= 2;
                                ackCount[i] = 0;
                            }
                        }
    					System.out.println("> Receive Ack " + ackNum);
    					
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
    	
    	public RDT_RCV(int _rePort) {
    		// System.out.println(_rePort);
    		try {
				this.socket = new DatagramSocket(_rePort);
			} catch (SocketException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
    	}
    }
    
    // RDT_SEND
    public class RDT_SEND extends Thread {
    	private DatagramSocket socket;
    	private int toPort;
    	private int rePort;
    	// private InetAddress address;
    	
    	public RDT_SEND(DatagramSocket _socket, int _toPort, int _rePort) {
    		socket = _socket;
    		toPort = _toPort;
    		rePort = _rePort;
    	}
    	
    	// Method for making packets.
    	public void run() {
    		try {
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
								// System.out.println(nextSeqNum);
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
									previous = 0;
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
								//isCompleted = true;
								//System.out.println("File transfer complete!");
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
    
    
    public void DownLoadFile(int _toPort, int _rePort, String path) {
    	System.out.println("> Begin getting " + fileName + " to " + address);
		
		int prevSeqNum = -1;			
		int expectedNum = 0;				

        int rePort = _toPort;
        int toPort = _rePort;    	
        
        try {
            // Create two sockets, one for receiving file, the others for sending ack messages.
            receiveSocket = new DatagramSocket(rePort);	
            toSocket = new DatagramSocket();				

            try {
                byte[] buffer = new byte[REC_LEN];									
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
                        System.out.println("> Receive sequence number: " + seqNum);
                        
                        // If packet received in order.
                        if (seqNum == expectedNum){
                            if (packetIn.getLength() == 12){
                                byte[] ackPkt = makePacket(-2);	
                                for (int i = 0; i < 20; i++) {
                                    toSocket.send(new DatagramPacket(ackPkt, ackPkt.length, address, toPort));
                                }
                                isCompleted = true;
                                System.out.println("> File transfer complete!");
                                continue;
                            }
                            
                            // else send ack
                            else{
                                sendAckNum(toSocket, toPort, seqNum);
                            }
                            
                            // First packet to transfer.
                            if (seqNum == 0 && prevSeqNum == -1){
                                int fileNameLength = ByteBuffer.wrap(getRangeArray(buffer, 12, 16)).getInt();
                                String fileName = new String(getRangeArray(buffer, 16, 16 + fileNameLength));
                                System.out.println("> fileName length: " + fileNameLength + ", fileName:" + fileName);
                                
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
                        else sendDuplicateMessage(toSocket, toPort, prevSeqNum);
                        
                    }
                    
                    // else packet is corrupted
                    else sendCorruptMessage(toSocket, toPort, prevSeqNum);
                    
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
	
    // Method for sending ack.
    public void sendAckNum(DatagramSocket toSocket, int toPort, int seqNum) throws IOException {
        byte[] ackPkt = makePacket(seqNum);
        toSocket.send(new DatagramPacket(ackPkt, ackPkt.length, address, toPort));
        System.out.println("> Send Ack " + seqNum + " to LFTP Server");
    }

    // Method For sending duplicate ack.
    public void sendDuplicateMessage(DatagramSocket toSocket, int toPort, int prevSeqNum) throws IOException {
        byte[] ackPkt = makePacket(prevSeqNum);
        toSocket.send(new DatagramPacket(ackPkt, ackPkt.length, address, toPort));
        System.out.println("> Send Duplicate Ack " + prevSeqNum + " to LFTP Server");
    }
    
    // Method For sending corrupt message.
    public void sendCorruptMessage(DatagramSocket toSocket, int toPort, int prevSeqNum) throws IOException {
        System.out.println("> Packet Corrupt!");
        byte[] ackPkt = makePacket(prevSeqNum);
        toSocket.send(new DatagramPacket(ackPkt, ackPkt.length, address, toPort));
        System.out.println("> Send Duplicate Ack " + prevSeqNum + " to LFTP Server");
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

    public static void main(String[] args) {
        // Current time.
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String current = df.format(System.currentTimeMillis());
        
        System.out.println("> " + current);
    	System.out.println("> Welcome to LFTP Server");
        System.out.println("> Usage: Download File   : LFTP lsend myserver mylargefile");
        System.out.println(">        Upload File     : LFTP lget  myserver mylargefile");
        System.out.println(">        Quit LFTP Server: LFTP quit  myserver end");
        System.out.print("> ");
        String defaultPath = "C:\\Users\\Alva\\Desktop\\LFTP_Client";
        Scanner input = new Scanner(System.in);
        String Command = input.nextLine();
        String[] Token = Command.split(" ");
        	
      
        String func = Token[1];
        String address = Token[2];
        String filename = Token[3];
        
        DatagramSocket socket = null;
		try {
			socket = new DatagramSocket();
		} catch (SocketException e) {
			e.printStackTrace();
		}
        try {
			socket.connect(InetAddress.getByName(address), 30000);
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
        System.out.println("> Successful connect to server at: " + address);
        
        DatagramPacket cmdPkt = new DatagramPacket(Command.getBytes(), Command.getBytes().length);
        try {
			socket.send(cmdPkt);
		} catch (IOException e) {
			e.printStackTrace();
		}
        System.out.println("> Send command " + func + " to LFTP Server");
        
        byte[] serverMsg = new byte[1024];
        DatagramPacket recPkt = new DatagramPacket(serverMsg, serverMsg.length);
        try {
			socket.receive(recPkt);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        
        String Msg = new String(recPkt.getData(), 0, recPkt.getData().length);
        String[] portList = Msg.split("::");
        
        int newToPort = Integer.parseInt(portList[0]);
        int newRePort = Integer.parseInt(portList[1]);
        
        System.out.println("> Receive new Port " + newToPort + " " + newRePort);
        socket.close();
        
        String[] saveFileName = filename.split("\\\\");
        String saveName = "LFTP_" + saveFileName[saveFileName.length-1];
        LFTPClient client = new LFTPClient(newRePort, newToPort, filename, saveName, address);
    	if (func.equals("lsend")) 
    		client.UploadFile(newRePort, newToPort);
    	else if (func.equals("lget"))
    		client.DownLoadFile(newToPort, newRePort, defaultPath);
    	else if (func.equals("quit"))
    		System.out.println("> LFTP Client Quit!");
    	else 
    		System.out.println("> Invalid commands");
    }
}
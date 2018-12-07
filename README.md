## Computer Networks Midterm Project

### Group Member

| Name   | Student No. |
| ------ | ----------- |
| 梁育诚 | 16340133    |
| 梁俊华 | 16340129    |

### How to Run

1. To run this project, you can use Eclipse or console. Here I'll show the console way.

2. Open two cmd(one for client and one for server) under the directory `LFTP Project/`, enter the following commands:

   ```bash
   $ javac *.java
   $ java LFTPServer // on server side
   $ java LFTPClient // on client side
   ```

3. Then you can enter LFTP commands in Client sid. Here are some examples:

   + `LFTP lsend 127.0.0.1 filename` for local tesing(filename should be a absolute path)
   + `LFTP lsend 172,19.47.xx filename`  for LAN testing(filename should be a absolute path)
   + `LFTP lget 127.0.0.1 filename`  for localtesting
   + `LFTP lget 172,19.47.xx filename`  for LAN testing

## Attention

This program can work perfectly in localhost or in LAN. However, because of NAT, we can't use this program between two hosts that are not in the same LAN. We're sorry about that and we'll improve it in the future. Thank you!



If you have any problems or suggestions, don't hesitate to contact us.

+ 梁俊华 liangjh45@mail2.sysu.edu.cn
+ 梁育诚 jacky14.liang@gmail.com
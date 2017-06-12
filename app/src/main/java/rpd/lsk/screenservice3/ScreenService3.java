package rpd.lsk.screenservice3;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.UiThread;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class ScreenService3 extends AppCompatActivity implements OnClickListener, ImageReader.OnImageAvailableListener {

    public static final String TAG = "LSK.Screen3";
    public static final String TAG2 = "Zombiesare";

    //media projection stuff
    public static final int REQUEST_CODE_SCREEN_CAPTURE = 1000;
    public MediaProjectionManager mMediaProjectionManager;
    public MediaProjection mMediaProjection;
    public VirtualDisplay mVirtualDisplay;
    //media projection will project screen to this reader and it will copy to Bitmap
    public ImageReader mImageReader;
    public Bitmap mScreenshot;
    public int mWidth = 400;
    public int mHeight = 640;
    //UI controls
    public FloatingActionButton fab1, fab2;
    public Toolbar toolbar;
    public Button bConnect;
    public EditText eIpAddress;
    public TextView tStatus;
    //    public ImageView mIV;
    //my instance
    ScreenService3 mInstance;

    //total image count and time ticking vars
    public long mTick1 = -1;
    public long mTick2 = -1;
    public long mSentSize = -1;
    public int count = 0;
    //sending status flag and Major and minor segments, net sending buffer(UDP :63KB)
    public boolean isSendingData = false;
    public int nMajorSegment = 1;
    public int nMinorSegment = 1;
    public ByteBuffer pbBuffer = ByteBuffer.allocate(63*1024 + 14);
    public int pbLength;


    public String eServerIpAddress;

    //public MyRunable myRunable = new MyRunable();
    //MyHandler2 mHandle2 = new MyHandler2();
    //A new thread for capture and send
    //public Handler mHandler = new Handler();
    CaptureThread thread1 = new CaptureThread();
    ConnectThread thread2_net = new ConnectThread();

    //Net Sockets
    public Socket socket = null;
    public DataOutputStream dataStreamOutToServer;
    //BufferedReader mBufferIn;
    public InputStreamReader mBufferIn;

    public int mQuality = 60;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInstance = this;

        //Init Controls
        setContentView(R.layout.activity_screen_service3);
        toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        eIpAddress = (EditText)findViewById(R.id.e_ipaddress);
        tStatus = (TextView)findViewById(R.id.t_status);
        bConnect = (Button)findViewById(R.id.b_connect);

        bConnect.setOnClickListener(this);

//        mIV = (ImageView)findViewById(R.id.imageView);
        fab1 = (FloatingActionButton)findViewById(R.id.fab1);   //start
        fab2 = (FloatingActionButton)findViewById(R.id.fab2);   //extra

        fab1.setOnClickListener(this);
        fab2.setOnClickListener(this);

        //Start requiring activity
        requireProjection();
        thread1.start();
        thread2_net.start();
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()){
            case R.id.fab1:
                if (!thread1.isAlive())
                    thread1.start();
                else
                    thread1.mHandler.sendMessage(thread1.mHandler.obtainMessage(0));
                break;
            case R.id.fab2:
                thread1.mHandler.removeMessages(0);
                break;
            case R.id.b_connect:
                tStatus.setText("Connecting...");
                if (!thread1.isAlive())
                    thread1.start();
                Message msg = thread1.mHandler.obtainMessage(0);
                msg.arg1 = 1;
                thread1.mHandler.sendMessage(msg);

                break;
        }
    }

    @Override
    public void onImageAvailable(ImageReader imageReader) {
        if (!thread1.isAlive())
            return;
        else {
            Message msg = thread1.mHandler.obtainMessage(0);
            msg.arg1 = 3;
            thread1.mHandler.sendMessage(msg);
        }
    }

    //Get mediaprojection service and grant user permission
    public void requireProjection(){
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        Intent permissionIntent = mMediaProjectionManager.createScreenCaptureIntent();
        startActivityForResult(permissionIntent, REQUEST_CODE_SCREEN_CAPTURE);
    }

    //Thread class for capture
    class CaptureThread extends Thread {
        public Handler mHandler;

        public void run() {
            Looper.prepare();

            mHandler = new Handler() {
                public void handleMessage(Message msg) {
                    switch (msg.arg1){
                        case 1: //Connect to server
                            connectServer();
                            break;
                        case 2: //Disconnect from server
                            disconnectServer();
                            break;
                        case 3: //Event from onFrameReady
                            try {
                                if (getScreenshot() != -1) {
                                    String str = String.format("--E-- captime:%d sendtime:%d size:%d mQuality:%d", mTick1, mTick2, mSentSize, mQuality);
                                    Log.e(TAG2, str);
                                } else {Log.e(TAG2, "----E--- -1");}
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            break;
                        default:    //Default Timing
                            try {
                                if (getScreenshot() != -1) {
                                    String str = String.format("==T== captime:%d sendtime:%d size:%d mQuality:%d", mTick1, mTick2, mSentSize, mQuality);
                                    Log.e(TAG2, str);
                                } else {Log.e(TAG2, "====T=== -1");}
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            mHandler.sendMessageDelayed(obtainMessage(0), 20);
                            break;
                    }
                }
            };

            mHandler.sendEmptyMessage(0);

            Looper.loop();
        }
    }

    //Thread class for network connecting
    class ConnectThread extends Thread {
        public Handler nHandler;

        public void run() {
            Looper.prepare();

            nHandler = new Handler() {
                public void handleMessage(Message msg) {
                    try {
                        while (socket == null || !socket.isConnected()){
                            //Not connected to TCP, so listen to UDP for servers
                            String lText;
                            byte[] lMsg = new byte[63 * 1024];
                            DatagramPacket dp = new DatagramPacket(lMsg, lMsg.length);
                            DatagramSocket ds = null;
                            try {
                                ds = new DatagramSocket(10216);
                                //disable timeout for testing
                                ds.setSoTimeout(1000);
                                ds.receive(dp);
                                lText = new String(lMsg, 0, dp.getLength());
                                Log.i("UDP packet received", lText);

                                //Received UDP packet, if a right one from server, try connect, else, again listen.
                                eServerIpAddress = dp.getAddress().getHostAddress();
                                connectServer();
                            } catch (Exception e) {
                                e.printStackTrace();
                            } finally {
                                if (ds != null) {
                                    ds.close();
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    //No need to loop here
                    //nHandler.sendMessageDelayed(obtainMessage(0), 50);
                }
            };

            nHandler.sendEmptyMessage(0);

            Looper.loop();
        }
    }

    public void showToasty(String str){
        Toast.makeText(this, str, Toast.LENGTH_SHORT).show();
    }

    //this called after user enable or disables media projection. = Creates ImageReader and virtual display from obtained media projection
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (REQUEST_CODE_SCREEN_CAPTURE == requestCode) {
            if (resultCode != RESULT_OK) {
                showToasty("User cancelled.");
                return;
            }
            mMediaProjection = mMediaProjectionManager.getMediaProjection(resultCode, intent);

            DisplayMetrics metrics = getResources().getDisplayMetrics();
            mWidth = metrics.widthPixels / 2;
            mHeight = metrics.heightPixels / 2;

            //    mWidth = 400;
            //    mHeight = 640;

            int density = metrics.densityDpi;

            Log.d(TAG,"setup VirtualDisplay");
            mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGB_565, 2);
            //mImageReader.setOnImageAvailableListener(this, null);
            mVirtualDisplay = mMediaProjection.createVirtualDisplay("Capturing Display",
                    mWidth, mHeight, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mImageReader.getSurface(), null, null);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_screen_service3, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    //Get screen image
    private long getScreenshot() {
        long tick = System.currentTimeMillis();
        if (socket != null && socket.isConnected()) {
            if (!isSendingData) {
                try {
                    Image image = mImageReader.acquireLatestImage();
                    if (image != null) {
                        Image.Plane[] planes = image.getPlanes();
                        ByteBuffer buffer = planes[0].getBuffer();

                        int pixelStride = planes[0].getPixelStride();
                        int rowStride = planes[0].getRowStride();
                        int rowPadding = rowStride - pixelStride * mWidth;

                        if (mScreenshot == null)
                            mScreenshot = Bitmap.createBitmap(mWidth + rowPadding / pixelStride, mHeight, Bitmap.Config.RGB_565);
                        mScreenshot.copyPixelsFromBuffer(buffer);

                        ByteArrayOutputStream stream = new ByteArrayOutputStream();


                        if (mSentSize < 30000){
                            if (mQuality > 60)
                                mQuality -= 5;
                            else
                                mQuality += 5;
                        } else if (mSentSize < 50000){
                            if (mQuality < 40)
                                mQuality += 5;
                            else {
                                mQuality -= 5;
                            }
                        } else if (mSentSize < 60000){
                            if (mQuality < 30)
                                mQuality += 5;
                            else {
                                mQuality -= 5;
                            }
                        } else {
                            if (mQuality < 20)
                                mQuality += 5;
                            else {
                                mQuality -= 5;
                            }
                        }

                        mScreenshot.compress(Bitmap.CompressFormat.JPEG, mQuality, stream);
/*
                    File file1 = new File("/mnt/sdcard/caps/z.jpg");
                    FileOutputStream fos = new FileOutputStream(file1);
                    fos.write(stream.toByteArray());

                    fos.flush();
                    fos.close();
*/
                        if (sendBytes(stream.toByteArray(), stream.size()) == -9) {
                            mTick2 = -9;
                        }

                        stream.close();

//                    mScreenshot.recycle();
                        image.close();
                        System.gc();

                        mTick1 = System.currentTimeMillis() - tick;
                        return mTick1;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return -1;
    }

    //put info to datagram
    public void AppendSegmentNumbers(ByteBuffer pbBuffer, int nOffset, int nMajorSegment, int nMinorSegment)
    {
        pbBuffer.put(nOffset, (byte) (nMajorSegment % 256));
        pbBuffer.put(nOffset + 1, (byte) (nMajorSegment / 256 % 256));
        pbBuffer.put(nOffset + 2, (byte) (nMajorSegment / 256 / 256 % 256));
        pbBuffer.put(nOffset + 3, (byte) (nMajorSegment / 256 / 256 / 256));
        pbBuffer.put(nOffset + 4, (byte) (nMinorSegment % 256));
        pbBuffer.put(nOffset + 5, (byte) (nMinorSegment / 256));
    }

    //send bytes.
    public long sendBytes(byte[] pbScreenShot, int mlength) {
        if (!(socket != null && socket.isConnected()))
            return -9;
        long tick = System.currentTimeMillis();

        isSendingData = true;
        nMinorSegment = 1;

        int nOffset = 0;
        int nMaxBufferLength = 63*1024;
        int nLeftLength = mlength;
        int nBufferLength;

        pbBuffer.limit(nMaxBufferLength + 14);

        try {

            do {
                pbBuffer.rewind();
                if (nMinorSegment == 1) {
                    if (nLeftLength <= nMaxBufferLength) {
                        pbBuffer.position(0);
                        pbBuffer.put("Start".getBytes("US-ASCII"), 0, 5);
                        pbBuffer.position(11);
                        pbBuffer.put(pbScreenShot, 0, mlength);
                        pbBuffer.position(nLeftLength + 11);
                        pbBuffer.put("End".getBytes("US-ASCII"), 0, 3);
                        AppendSegmentNumbers(pbBuffer, 5, nMajorSegment, nMinorSegment);
                        nBufferLength = Math.min(nLeftLength, nMaxBufferLength) + 14;
                    } else {
                        pbBuffer.position(0);
                        pbBuffer.put("Start".getBytes("US-ASCII"), 0, 5);
                        pbBuffer.position(11);
                        byte buf2[] = Arrays.copyOfRange(pbScreenShot, nOffset, nOffset + nMaxBufferLength);
                        pbBuffer.put(buf2, 0, Math.min(nLeftLength, nMaxBufferLength));
                        AppendSegmentNumbers(pbBuffer, 5, nMajorSegment, nMinorSegment);
                        nBufferLength = Math.min(nLeftLength, nMaxBufferLength) + 11;
                    }
                } else if (nLeftLength <= nMaxBufferLength) {
                    pbBuffer.position(6);
                    byte buf3[] = Arrays.copyOfRange(pbScreenShot, nOffset, nOffset + nLeftLength);
                    pbBuffer.put(buf3, 0, nLeftLength);
                    pbBuffer.position(6 + nLeftLength);
                    pbBuffer.put("End".getBytes("US-ASCII"), 0, 3);
                    AppendSegmentNumbers(pbBuffer, 0, nMajorSegment, nMinorSegment);
                    nBufferLength = nLeftLength + 9;
                } else {
                    pbBuffer.position(6);
                    byte buf4[] = Arrays.copyOfRange(pbScreenShot, nOffset, nOffset + nMaxBufferLength);
                    pbBuffer.put(buf4, 0, nMaxBufferLength);
                    AppendSegmentNumbers(pbBuffer, 0, nMajorSegment, nMinorSegment);
                    nBufferLength = nMaxBufferLength + 6;
                }

                nOffset += nMaxBufferLength;
                nLeftLength -= nMaxBufferLength;

                //UDP
                //DatagramPacket p = new DatagramPacket(pbBuffer.array(), nBufferLength,local,server_port);
                //s.send(p);

                //TCP
                pbBuffer.limit(nBufferLength);
                pbBuffer.rewind();
                byte buf5[] = Arrays.copyOfRange(pbBuffer.array(), 0, nBufferLength);

                socket.setSendBufferSize(nBufferLength);

                while (true) {
                    String mServerMessage = "";
                    int a = 0;
                    try {
                        char [] buf222 = new char[socket.getReceiveBufferSize()];
                        a = mBufferIn.read(buf222);

                    } catch (Exception e){
                    }
                    if (a > 0) {
                        //call the method messageReceived from MyActivity class
                        //if (mServerMessage.equals("Next"))
                            break;
                    }

                }

                dataStreamOutToServer.write(buf5);
                dataStreamOutToServer.flush();
                mSentSize = nBufferLength;

                nMinorSegment++;

            } while (nLeftLength > 0);

            nMajorSegment++;
            isSendingData = false;

        } catch (Exception e){
            e.printStackTrace();
            disconnectServer();
            isSendingData = false;
        }
        mTick2 = System.currentTimeMillis() - tick;
        isSendingData = false;
        return 0;
    }

    public int connectServer(){
        disconnectServer();
        try {
            socket = new Socket(eIpAddress.getText().toString(), 10215);
            socket.setSoTimeout(20);
        } catch (IOException e) {
            e.printStackTrace();    //Can not connect.

            return -2;
        }
        try {
            dataStreamOutToServer = new DataOutputStream(socket.getOutputStream());
            //mBufferIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            mBufferIn = new InputStreamReader(socket.getInputStream());
            //dataStreamOutToServer.writeChars("asdfsadf");
            //dataStreamOutToServer.flush();
            int bufsize = socket.getSendBufferSize();
            bufsize = bufsize + 1;
        } catch (Exception e){
            e.printStackTrace();

            try {
                socket.close();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            return -3;
        }

        return 0;
    }

    public int disconnectServer(){
        try {
            if (dataStreamOutToServer != null){
                dataStreamOutToServer.close();
            }
            if (socket != null)
                socket.close();
        } catch (Exception e){
            e.printStackTrace();
            return -1;
        }
        thread2_net.nHandler.sendMessage(thread2_net.nHandler.obtainMessage(0));
        return 0;
    }
}

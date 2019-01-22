package com.example.taichiabe.receiver;
/*=========================================================*
 * システム：受信・リアルタイムFFT
 *==========================================================*/
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.Switch;
import android.widget.EditText;
import android.widget.TextView;

public class MainActivity extends Activity implements OnCheckedChangeListener {

    int RECVFREQ;
    double DECIBEL;
    int FFTPOINT;

    //サンプリングレート
    int RecvSR = 44100;
    //FFTのポイント数（2の累乗）
    int fftSize = 4096;
    //デシベルベースラインの設定
    double dB_baseline = Math.pow(2, 15) * fftSize * Math.sqrt(2);
    //分解能の計算
    double resol = RecvSR / (double) fftSize;
    Vibrator vib;
    AudioRecord audioRec = null;
    boolean bIsRecording = false;
    int RecvBufSize;
    Thread fft;
    TimeMeasure tm;
    SdLog sdlog;

    String filename;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView RecvfreqText = findViewById(R.id.RecvfreqText);
        RecvfreqText.setText(R.string.RecvfreqText);
        TextView decibelText = findViewById(R.id.decibelText);
        decibelText.setText(R.string.decibelText);
        Switch switch1 = findViewById(R.id.Switch);
        switch1.setOnCheckedChangeListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {

        if(isChecked) {

            EditText RecvfreqEdit = findViewById(R.id.RecvfreqEdit);
            EditText decibelEdit = findViewById(R.id.decibelEdit);
            RECVFREQ = Integer.parseInt(RecvfreqEdit.getText().toString());
            DECIBEL = Integer.parseInt(decibelEdit.getText().toString());

            filename = String.valueOf(System.currentTimeMillis());


            //TODO: fftポイントが4096, 8192, 14862のどれでもうまく適用されるように変更
            //FFTPOINT = 14862;

            //受信周波数からFFTポイントを算出
            FFTPOINT = (int)(2 * RECVFREQ / resol);
            if(FFTPOINT % 2 == 1)
                FFTPOINT = FFTPOINT + 1;


            /*
            if(RECVFREQ == 18000)      FFTPOINT = 3344;
            else if(RECVFREQ == 18500) FFTPOINT = 3436;
            else if(RECVFREQ == 19000) FFTPOINT = 3530;
            else if(RECVFREQ == 19500) FFTPOINT = 3622;
            else if(RECVFREQ == 20000) FFTPOINT = 3716;
            else if(RECVFREQ == 20500) FFTPOINT = 3808;
            else if(RECVFREQ == 21000) FFTPOINT = 3900;
            else if(RECVFREQ == 21500) FFTPOINT = 3994;
            else if(RECVFREQ == 22000) FFTPOINT = 4086;
            */

            //実験用デバイスではRecvBufSize = 3584
            RecvBufSize = AudioRecord.getMinBufferSize(
                    RecvSR,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            //最終的にFFTクラスの4096以上を確保するためbufferSizeInBytes = RecvBufSize * 4
            audioRec = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    RecvSR,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    RecvBufSize * 4);

            //Vibratorクラスのインスタンス取得
            vib = (Vibrator)getSystemService(VIBRATOR_SERVICE);

            //TimeMeasureクラスのインスタンス取得
            tm = new TimeMeasure();

            audioRec.startRecording();
            bIsRecording = true;

            //フーリエ解析スレッドを生成
            fft = new Thread(new Runnable() {
                @Override
                public void run() {

                    byte buf[] = new byte[RecvBufSize * 4];
                    while (bIsRecording) {

                        //計測開始
                        tm.start();

                        audioRec.read(buf, 0, buf.length);

                        //エンディアン変換
                        //配列bufをもとにByteBufferオブジェクトbfを作成
                        ByteBuffer bf = ByteBuffer.wrap(buf);
                        //バッファをクリア（データは削除されない）
                        bf.clear();
                        //リトルエンディアンに変更
                        bf.order(ByteOrder.LITTLE_ENDIAN);
                        short[] s = new short[RecvBufSize * 2];
                        //位置から容量まで
                        for (int i = bf.position(); i < bf.capacity() / 2; i++) {
                            //short値を読むための相対getメソッド
                            //現在位置の2バイトを読み出す
                            s[i] = bf.getShort();
                        }

                        //FFTクラスの作成と値の引き渡し
                        FFT4g fft = new FFT4g(fftSize);
                        double[] FFTdata = new double[fftSize];
                        for (int i = (int)(2 * 16000 / resol); i < fftSize; i++) {
                            FFTdata[i] = (double) s[i];
                        }
                        fft.rdft(1, FFTdata);

                        // パワースペクトル・デシベルの計算
                        double[] ps = new double[fftSize / 2];
                        double[] dbfs = new double[fftSize / 2];
                        for (int i = (int)(2 * 16000 / resol); i < fftSize; i += 2) {
                            //dbfs[i / 2] = (int) (20 * Math.log10(Math.sqrt(Math.pow(FFTdata[i], 2) + Math.pow(FFTdata[i + 1], 2)) / dB_baseline));
                            ps[i / 2] = Math.sqrt(Math.pow(FFTdata[i], 2) + Math.pow(FFTdata[i + 1], 2));
                            dbfs[i / 2] = (int) (20 * Math.log10(ps[i / 2] / dB_baseline));

                            /*
                            // D.E.B.U.G
                            if(i == FFTPOINT) {
                                sdlog.put("freq" + filename, String.format("%.3f", FFTPOINT * resol / 2) + " : " + String.valueOf(dbfs[i / 2]));
                            }
                        */

                        }

                        //ドップラー効果考慮
                        int de_start = (int)(2 * RECVFREQ / resol);
                        if(de_start % 2 == 1)
                            de_start = de_start + 1;

                        int de_end = (int)(2 * (RECVFREQ+500) / resol);
                        if(de_end % 2 == 1)
                            de_end = de_end + 1;

                        for(int j = de_start / 2; j < de_end / 2; j++) {
                            if(dbfs[j] >= DECIBEL) {
                                //sdlog.put("powerspectrum1-" + filename, String.format("%.3f", j * resol) + " : " + String.valueOf(ps[j]));
                                //検知周波数
                                sdlog.put("freq1-" + filename, String.format("%.3f", j * resol) + " : " + String.valueOf(dbfs[j]));
                                vib.cancel();
                                vib.vibrate(200);
                                break;
                            }
                        }



                        //計測終了
                        tm.finish();
                        sdlog.put("time1-" + filename, "process time:" + tm.getResult().substring(0, 5));
                        //処理時間出力
                        tm.printResult();

                    }
                    audioRec.stop();
                    audioRec.release();
                }

            });
            //スレッドのスタート
            fft.start();

        } else {

            if(audioRec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                vib.cancel();
                audioRec.stop();
                //audioRec.release();
                bIsRecording = false;
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        if(audioRec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            audioRec.stop();
            bIsRecording = false;
        }

    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if(audioRec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            audioRec.stop();
            audioRec.release();
            bIsRecording = false;
        }
    }
}
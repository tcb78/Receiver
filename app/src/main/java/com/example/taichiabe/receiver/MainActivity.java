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
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.Switch;
import android.widget.EditText;
import android.widget.TextView;

public class MainActivity extends Activity implements OnCheckedChangeListener {

    //サンプリングレート
    public static final int SAMPLING_RATE = 44100;
    //FFTのフレーム数（2の累乗）
    public static final int FFT_SIZE = 4096;
    //デシベルベースラインの設定
    public static final double DB_BASELINE = Math.pow(2, 15) * FFT_SIZE * Math.sqrt(2);
    //分解能の計算
    public static final double RESOLUTION = SAMPLING_RATE / (double) FFT_SIZE;
    private Vibrator vib;
    private AudioRecord audioRec = null;
    boolean isRecording = false;
    Thread fft;
    private TimeMeasure tm;
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView receivingFreqText = findViewById(R.id.receivingFreqText);
        receivingFreqText.setText(R.string.receivingFreqText);
        TextView thresholdText = findViewById(R.id.thresholdText);
        thresholdText.setText(R.string.thresholdText);
        Switch receivingSwitch = findViewById(R.id.receivingSwitch);
        receivingSwitch.setOnCheckedChangeListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if(isChecked) {
            EditText receivingFreqEdit = findViewById(R.id.receivingFreqEdit);
            EditText thresholdEdit = findViewById(R.id.thresholdEdit);

            final int RECEIVING_FREQ = Integer.parseInt(receivingFreqEdit.getText().toString());
            final double THRESHOLD = Integer.parseInt(thresholdEdit.getText().toString());

            //実験用デバイスではminBufSize = 3584
            final int MIN_BUFFER_SIZE = AudioRecord.getMinBufferSize(
                    SAMPLING_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            final int RECORD_BUFFER_SIZE = MIN_BUFFER_SIZE * 4;

            //最終的にFFTクラスの4096以上を確保するためbufferSizeInBytes = RecvBufSize * 4
            audioRec = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLING_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    RECORD_BUFFER_SIZE);

            //Vibratorクラスのインスタンス取得
            vib = (Vibrator)getSystemService(VIBRATOR_SERVICE);

            //TimeMeasureクラスのインスタンス取得
            tm = new TimeMeasure();

            audioRec.startRecording();
            isRecording = true;

            //フーリエ解析スレッドを生成
            fft = new Thread(new Runnable() {
                @Override
                public void run() {

                    byte[] recordData = new byte[RECORD_BUFFER_SIZE];
                    while (isRecording) {
                        //計測開始
                        tm.startMeasure();

                        audioRec.read(recordData, 0, recordData.length);
                        //エンディアン変換
                        short[] shortData = toLittleEndian(recordData, RECORD_BUFFER_SIZE);
                        //FFTクラスの作成と値の引き出し
                        double[] fftData = fastFourierTransform(shortData);
                        //パワースペクトル・デシベルの計算
                        double[] decibelFrequencySpectrum = computePowerSpectrum(fftData);
                        //ドップラー効果を考慮した接近検知
                        detectApproaching(RECEIVING_FREQ, THRESHOLD, decibelFrequencySpectrum);
                        //計測終了
                        tm.finishMeasure();
                        //処理時間出力
                        tm.printTimeSec();
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
                isRecording = false;
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if(audioRec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            audioRec.stop();
            isRecording = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if(audioRec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            audioRec.stop();
            audioRec.release();
            isRecording = false;
        }
    }

    /**
     * エンディアン変換
     * @param buf 受信バッファデータ
     * @param bufferSize 受信バッファサイズ
     * @return エンディアン変換後short型データ
     */
    public short[] toLittleEndian(byte[] buf, int bufferSize) {
        //エンディアン変換
        //配列bufをもとにByteBufferオブジェクトbfを作成
        ByteBuffer bf = ByteBuffer.wrap(buf);
        //バッファをクリア（データは削除されない）
        bf.clear();
        //リトルエンディアンに変更
        bf.order(ByteOrder.LITTLE_ENDIAN);
        short[] shortData = new short[bufferSize / 2];

        int bfBegin = bf.position();
        int bfEnd = bf.capacity() / 2;
        for (int i = bfBegin; i < bfEnd; i++) {
            //short値を読むための相対getメソッド
            //現在位置の2バイトを読み出す
            shortData[i] = bf.getShort();
        }
        return shortData;
    }

    /**
     * 高速フーリエ変換
     * @param shortData エンディアン変換後データ
     * @return フーリエ変換後データ
     */
    public double[] fastFourierTransform(short[] shortData) {
        //FFTクラスの作成と値の引き渡し
        FFT4g fft = new FFT4g(FFT_SIZE);
        double[] fftData = new double[FFT_SIZE];
        for(int i = 0; i < FFT_SIZE; i++) {
            fftData[i] = (double) shortData[i];
        }
        fft.rdft(1, fftData);

        return fftData;
    }

    /**
     * パワースペクトル・デシベルの計算
     * @param fftData フーリエ変換後のデータ
     * @return デシベル値
     */
    public double[] computePowerSpectrum(double[] fftData) {
        //パワースペクトル・デシベルの計算
        double[] powerSpectrum = new double[FFT_SIZE / 2];
        //DeciBel Frequency Spectrum
        double[] decibelFrequencySpectrum = new double[FFT_SIZE / 2];
        for(int i = 0; i < FFT_SIZE; i += 2) {
            powerSpectrum[i / 2] = Math.sqrt(Math.pow(fftData[i], 2) + Math.pow(fftData[i + 1], 2));
            decibelFrequencySpectrum[i / 2] = (int) (20 * Math.log10(powerSpectrum[i / 2] / DB_BASELINE));
        }
        return decibelFrequencySpectrum;
    }

    /**
     * 接近検知アルゴリズム
     * @param freq 受信周波数
     * @param decibel 検知デシベル値
     * @param decibelFrequencySpectrum デシベル値周波数成分
     */
    public void detectApproaching(int freq, double decibel, double[] decibelFrequencySpectrum) {
        //ドップラー効果考慮 Doppler Effect
        //500Hzの幅で接近検知
        int detectFrameBegin = frameSetting(freq);
        int detectFrameEnd = frameSetting(freq + 500);
        for(int j = detectFrameBegin; j < detectFrameEnd; j++) {
            if(decibelFrequencySpectrum[j] > decibel) {
                vib.cancel();
                vib.vibrate(200);
                break;
            }
        }
    }

    /**
     * 周波数からフレーム設定
     * @param freq 周波数
     * @return フレーム
     */
    public int frameSetting(int freq) {
        int frame = (int)(2 * freq / RESOLUTION);
        if(frame % 2 == 1) {
            frame++;
        }
        return frame / 2;
    }
}

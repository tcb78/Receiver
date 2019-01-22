package com.example.taichiabe.receiver;

import java.text.NumberFormat;
import android.util.Log;

public class TimeMeasure {

    //ナノ秒から秒に換算するための割数
    private static final int DIVISOR = 1000000000;

    //NumberFormatインスタンス
    private static final NumberFormat nf = NumberFormat.getInstance();

    //計測開始時間
    private double startTime;

    //計測終了時間
    private double endTime;

    //コンストラクタ
    public TimeMeasure() {
        //小数部分の最大表示桁数を10桁で指定
        nf.setMaximumFractionDigits(10);
    }

    //計測開始時間をセット.
    public void start() {
        this.startTime = System.nanoTime();
    }

    //計測終了時間をセット.
    public void finish() {
        this.endTime = System.nanoTime();
    }

    /**計測結果を取得.
     * @return 指定範囲の処理にかかった時間を秒で返す
     */
    public String getResult() {
        return nf.format((this.endTime - this.startTime) / DIVISOR);
    }

    /**
     *計測結果を出力.
     *指定範囲の処理にかかった時間を秒で出力する.
     */
    public void printResult() {
        Log.d("TimeMeasure",getResult());
        this.startTime = this.endTime;
    }

}

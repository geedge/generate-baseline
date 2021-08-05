package cn.mesalab.service.algorithm;

import cn.mesalab.config.ApplicationConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author yjy
 * @version 1.0
 * kalman滤波器
 * @date 2021/7/25 1:42 下午
 */

public class KalmanFilter {
    private Integer predict;
    private Integer current;
    private Integer estimate;
    private double pdelt;
    private double mdelt;
    private double Gauss;
    private double kalmanGain;
    private final static double R = ApplicationConfig.BASELINE_KALMAN_R;

    public KalmanFilter() {
        initial();
    }

    public void initial(){
        pdelt = ApplicationConfig.BASELINE_KALMAN_P;
    }

    private ArrayList<Integer> smoothSeries;
    private ArrayList<Integer> forecastSeries;

    public Integer calSingleKalPoint(Integer oldValue, Integer value){
        //第一个估计值
        predict = oldValue;
        current = value;
        //估计方差
        kalmanGain = pdelt/(pdelt + R);
        //估计值
        estimate = (int) (kalmanGain * (current - predict) + predict);
        //新的估计方差
        mdelt = (1-kalmanGain) * pdelt ;

        return estimate;
    }

    public void forcast(List<Integer> historicalSeries, Integer length){
        // 滤波
        int oldvalue = (historicalSeries.stream().mapToInt(Integer::intValue).sum())/historicalSeries.size();
        smoothSeries = new ArrayList<Integer>();
        for(int i = 0; i < historicalSeries.size(); i++){
            int value = historicalSeries.get(i);
            oldvalue = calSingleKalPoint(oldvalue,value);
            smoothSeries.add(oldvalue);
        }

        // 平均
        forecastSeries = new ArrayList<>();
        Integer partitonNum = historicalSeries.size()/length;
        for(int i = 0; i<length; i++){
            long sum = 0;
            for (int period=0; period<partitonNum; period++){
                sum += smoothSeries.get(length*period+i);
            }
            forecastSeries.add((int)sum/partitonNum);
        }
    }

    public ArrayList<Integer> getSmoothSeries() {
        return smoothSeries;
    }

    public ArrayList<Integer> getAllRangeSeries() {
        ArrayList<Integer> results = new ArrayList<>();
        results.addAll(smoothSeries);
        results.addAll(forecastSeries);
        return results;
    }

    public ArrayList<Integer> getForecastSeries() {
        return forecastSeries;
    }
}

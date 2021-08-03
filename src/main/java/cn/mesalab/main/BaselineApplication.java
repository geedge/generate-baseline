package cn.mesalab.main;

import cn.mesalab.service.BaselineGeneration;
import sun.rmi.runtime.Log;

/**
 * @author yjy
 * @version 1.0
 * @date 2021/7/23 5:34 下午
 */
public class BaselineApplication {
    public static void main(String[] args) {
        BaselineGeneration.perform();
    }
}

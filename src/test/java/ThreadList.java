import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * @author yjy
 * @version 1.0
 * @date 2021/7/30 11:09 上午
 */
public class ThreadList {

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        List<String> list = new ArrayList<>(); //造list数据
        for (int i = 0; i < 5300; i++) {
            list.add("" + i);
        }
        // 计算线程数
        int threadSize = 500;//每500条数据开启一个线程
        int remainder = list.size() % threadSize;  //取余
        int threadNum = 0; //线程数
        if (remainder == 0) { //能整除500
            threadNum = list.size() / threadSize;
        } else {  //不能整除线程数要加1
            threadNum = list.size() / threadSize + 1;
        }

        ExecutorService eService = Executors.newFixedThreadPool(threadNum); //创建一个线程池
        List<Callable<String>> cList = new ArrayList<>();  //定义添加线程的集合
        Callable<String> task = null;  //创建单个线程
        List<String> sList = null;

        for (int i = 0; i < threadNum; i++) {  //每个线程中加入分段数据

            if (i == threadNum - 1) {
                sList = list.subList(i * threadSize, list.size());
            } else {
                sList = list.subList(i * threadSize, (i + 1) * threadSize);
            }

            final List<String> nowList = sList;
            //创建单个线程
            task = new Callable<String>() {
                @Override
                public String call() throws Exception {
                    StringBuffer sb = new StringBuffer();
                    for (int j = 0; j < nowList.size(); j++) {
                        sb.append("" + nowList.get(j));
                    }
                    return sb.toString();
                }
            };
            cList.add(task); //添加线程
        }
        List<Future<String>> results = eService.invokeAll(cList); //执行所有创建的线程，并获取返回值（会把所有线程的返回值都返回）

        for (Future<String> str : results) {  //打印返回值
            System.out.println(str.get());
        }
        eService.shutdown();
    }
}

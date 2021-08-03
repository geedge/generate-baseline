package cn.mesalab.service;

/**
 * @author yjy
 * @version 1.0
 * @date 2021/8/3 11:21 上午
 */

import cn.mesalab.config.ApplicationConfig;
import cn.mesalab.dao.DruidData;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.ArrayWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableUtils;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HBaseTest {
    public static void main(String[] args) throws IOException {
        org.apache.hadoop.conf.Configuration config = HBaseConfiguration.create();

        config.set(HConstants.ZOOKEEPER_QUORUM, ApplicationConfig.HBASE_ZOOKEEPER_QUORUM);
        config.set(HConstants.ZOOKEEPER_CLIENT_PORT, ApplicationConfig.HBASE_ZOOKEEPER_CLIENT_PORT);

        TableName tableName  = TableName.valueOf(ApplicationConfig.HBASE_TABLE);
        Connection conn = ConnectionFactory.createConnection(config);
        Table table = conn.getTable(tableName);


//        DruidData druidData = DruidData.getInstance();
//        ArrayList<String> destinationIps = druidData.getServerIpList();
        List<String> ips = Arrays.asList(
                "192.168.1.1",
                "192.168.1.2",
                "192.168.1.3",
                "192.168.1.4",
                "192.168.1.5",
                "192.168.1.6",
                "192.168.1.7",
                "192.168.1.8",
                "192.168.10.1",
                "192.168.10.2",
                "192.168.10.3",
                "192.168.10.4",
                "192.168.10.5",
                "192.168.10.6",
                "192.168.10.7",
                "192.168.10.8"
        );

        for (String ip : ips){
            Get abcGet = new Get(Bytes.toBytes(ip));
            Result r = table.get(abcGet);
            ArrayWritable w = new ArrayWritable(IntWritable.class);
            List<String> attackTypeList = Arrays.asList(
                    "TCP SYN Flood",
                    "ICMP Flood"
//                    "UDP Flood",
//                    "DNS Amplification"
            );
                for (String attackType : attackTypeList){
                    byte[] session_nums = r.getValue(Bytes.toBytes(attackType), Bytes.toBytes("session_num"));
                    if (session_nums==null){
                        continue;
                    }
                    w.readFields(new DataInputStream(new ByteArrayInputStream(session_nums)));
                    ArrayList<Integer> arr2 = fromWritable(w);
                    System.out.println(ip + "-" + attackType + ": " + arr2.toString());
                }

        }



//        int[] arr = new int[144];
//        Arrays.fill(arr, 100);
//        List<String> ips = Arrays.asList(
//                "192.168.1.1",
//                "192.168.1.2",
//                "192.168.1.3",
//                "192.168.1.4",
//                "192.168.1.5",
//                "192.168.1.6",
//                "192.168.1.7",
//                "192.168.1.8",
//                "192.168.10.1",
//                "192.168.10.2",
//                "192.168.10.3",
//                "192.168.10.4",
//                "192.168.10.5",
//                "192.168.10.6",
//                "192.168.10.7",
//                "192.168.10.8"
//        );
//
//        for (String ip : ips){
//            Put put = new Put(Bytes.toBytes(ip));
//            put.addColumn(Bytes.toBytes("ICMP Flood"),Bytes.toBytes("session_num"), WritableUtils.toByteArray(toWritable(arr)));
//            table.put(put);
//        }



    }

    public static Writable toWritable(int[] arr) {
        Writable[] content = new Writable[arr.length];
        for (int i = 0; i < content.length; i++) {
            content[i] = new IntWritable(arr[i]);
        }
        return new ArrayWritable(IntWritable.class, content);
    }

    public static ArrayList<Integer> fromWritable(ArrayWritable writable) {
        Writable[] writables = ((ArrayWritable) writable).get();
        ArrayList<Integer> list = new ArrayList<Integer>(writables.length);
        for (Writable wrt : writables) {
            list.add(((IntWritable)wrt).get());
        }
        return list;
    }

}

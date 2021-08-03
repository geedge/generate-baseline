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


        DruidData druidData = DruidData.getInstance();
        ArrayList<String> destinationIps = druidData.getServerIpList();

        for (String ip : destinationIps){
            Get abcGet = new Get(Bytes.toBytes(ip));
            Result r = table.get(abcGet);
            ArrayWritable w = new ArrayWritable(IntWritable.class);
            List<String> attackTypeList = Arrays.asList(
                    "TCP SYN Flood",
                    "ICMP Flood",
                    "UDP Flood",
                    "DNS Amplification"
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

//        Get abcGet = new Get(Bytes.toBytes("1.0.0.1"));
//        Result r = table.get(abcGet);
//        ArrayWritable w = new ArrayWritable(IntWritable.class);
//        w.readFields(new DataInputStream(new ByteArrayInputStream(r.getValue(Bytes.toBytes("TCP SYN Flood"), Bytes.toBytes("session_num")))));
//        ArrayList<Integer> arr2 = fromWritable(w);
//        System.out.println(arr2.toString());


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

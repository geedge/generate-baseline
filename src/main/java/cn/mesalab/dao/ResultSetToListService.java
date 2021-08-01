package cn.mesalab.dao;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;


/**
 * @author yjy
 * @version 1.0
 * @date 2021/7/24 4:27 下午
 */
public interface ResultSetToListService {
    /**
     * SELECT * FROM websites
     * 查询所有记录，以List返回
     * list对象的每一个元素都是一条记录
     * 每条记录保存在Map<String, Object>里面，String类型指字段名字，Object对应字段值
     *
     * @param rs
     * @return List<Map < String, Object>>
     */
    public List<Map<String, Object>> selectAll(ResultSet rs);
}
package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.bean.History;

import java.util.List;

@Dao
public abstract class HistoryDao extends BaseDao<History> {

    @Query("SELECT * FROM History")
    public abstract List<History> findAll();

    /** 只取某个点播源下的观看记录：同步按源隔离，别把别的源的记录一起推走 */
    @Query("SELECT * FROM History WHERE cid = :cid")
    public abstract List<History> findByCid(int cid);

    @Query("SELECT * FROM History WHERE cid = :cid AND createTime >= :createTime ORDER BY createTime DESC LIMIT 60")
    public abstract List<History> find(int cid, long createTime);

    /** 最近观看：跨点播源取（观看记录跟着账号走，不跟源走） */
    @Query("SELECT * FROM History WHERE createTime >= :createTime ORDER BY createTime DESC LIMIT 60")
    public abstract List<History> findRecent(long createTime);

    @Query("SELECT * FROM History WHERE cid = :cid AND `key` = :key")
    public abstract History find(int cid, String key);

    @Query("SELECT * FROM History WHERE cid = :cid AND vodName = :vodName ORDER BY createTime DESC")
    public abstract List<History> findByName(int cid, String vodName);

    /**
     * 按片名跨点播源查：换源/换站之后要靠它把原来那条记录的进度接过来
     * （原名那条可能还挂在旧源 cid 底下，只查当前源就接不上）。
     */
    @Query("SELECT * FROM History WHERE vodName = :vodName ORDER BY createTime DESC")
    public abstract List<History> findByName(String vodName);

    @Query("DELETE FROM History WHERE cid = :cid AND `key` = :key")
    public abstract void delete(int cid, String key);

    @Query("DELETE FROM History WHERE cid = :cid")
    public abstract void delete(int cid);

    @Query("DELETE FROM History")
    public abstract void delete();
}

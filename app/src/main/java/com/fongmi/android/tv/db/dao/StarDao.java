package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.bean.Star;

import java.util.List;

@Dao
public abstract class StarDao extends BaseDao<Star> {

    /** 界面上要看的：只给还没取消关注的，按关注时间新的在前 */
    @Query("SELECT * FROM Star WHERE deleted = 0 ORDER BY createTime DESC")
    public abstract List<Star> getAll();

    /** 同步上传用：连取消关注那条一起带走 */
    @Query("SELECT * FROM Star")
    public abstract List<Star> findAll();

    @Query("SELECT * FROM Star WHERE name = :name")
    public abstract Star find(String name);

    @Query("DELETE FROM Star")
    public abstract void deleteAll();
}

package com.bemodel.modeling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bm_release")
public class Release {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String versionTag;
    private String changeSummary;
    private Integer elementCount;
    private String snapshotJson;
    private String releasedBy;
    private LocalDateTime createdAt;
}

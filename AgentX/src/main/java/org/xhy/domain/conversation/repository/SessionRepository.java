package org.xhy.domain.conversation.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.xhy.domain.conversation.model.SessionEntity;
import org.xhy.infrastructure.repository.MyBatisPlusExtRepository;

/** 浼氳瘽浠撳簱鎺ュ彛 */
@Mapper
public interface SessionRepository extends MyBatisPlusExtRepository<SessionEntity> {

    @Update("""
            UPDATE sessions
            SET metadata = CAST(#{metadata,jdbcType=VARCHAR} AS jsonb),
                updated_at = CURRENT_TIMESTAMP
            WHERE deleted_at IS NULL
              AND id = #{sessionId}
              AND user_id = #{userId}
            """)
    int updateSessionMetadata(@Param("sessionId") String sessionId,
                              @Param("userId") String userId,
                              @Param("metadata") String metadata);
}

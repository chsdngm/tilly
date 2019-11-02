package com.imbananko.tilly.repository

import com.imbananko.tilly.model.VoteValue
import com.imbananko.tilly.utility.SqlQueries
import org.springframework.data.redis.core.HashOperations
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import javax.annotation.PostConstruct
import javax.annotation.Resource
import com.imbananko.tilly.model.VoteEntity as V

@Repository
class VoteRepository(private val template: NamedParameterJdbcTemplate, private val queries: SqlQueries) {
  @Resource(name = "stringRedisTemplate")
  private lateinit var hashOperations: HashOperations<String, String, String>

  @PostConstruct
  fun init() {
    template.query(queries.getFromConfOrFail("findAllVotes")) { rs: ResultSet, _: Int ->
      V(
          rs.getLong("chat_id"),
          rs.getInt("message_id"),
          rs.getInt("voter_id"),
          VoteValue.valueOf(rs.getString("value"))
      )
    }.forEach { vote: V? ->
      hashOperations.put("vote:${vote?.chatId}:${vote?.messageId}",
          vote?.voterId.toString(),
          vote?.value?.name ?: "")
    }
  }

  fun exists(vote: V): Boolean =
      template.queryForObject(queries.getFromConfOrFail("voteExists"), getParams(vote), Boolean::class.java) ?: false

  fun insertOrUpdate(vote: V): Int = template.update(queries.getFromConfOrFail("insertOrUpdateVote"), getParams(vote))

  fun delete(vote: V): Unit {
    template.update(queries.getFromConfOrFail("deleteVote"), getParams(vote))
  }

  fun getStats(chatId: Long, messageId: Int): Map<VoteValue, Int> =
      template.query(
          queries.getFromConfOrFail("findVoteStats"),
          MapSqlParameterSource("chatId", chatId).addValue("messageId", messageId)
      ) { rs, _ -> VoteValue.valueOf(rs.getString("value")) to rs.getLong("count").toInt() }.toMap()

  fun cacheVote(vote: V): HashMap<VoteValue, Int> {
    val redisKey = "vote:${vote.chatId}:${vote.messageId}"

    if (hashOperations.get(redisKey, vote.voterId.toString()) == vote.value.name)
      hashOperations.delete(redisKey, vote.voterId.toString())
    else
      hashOperations.put(redisKey, vote.voterId.toString(), vote.value.name)

    val stats = HashMap<VoteValue, Int>()
    hashOperations.entries(redisKey)
        .map { entry ->
          stats.merge(VoteValue.valueOf(entry.value), 1) { old, new -> old + new }
        }
    return stats
  }

  private fun getParams(vote: V): MapSqlParameterSource =
      MapSqlParameterSource("chatId", vote.chatId)
          .addValue("messageId", vote.messageId)
          .addValue("voterId", vote.voterId)
          .addValue("value", vote.value.name)
}
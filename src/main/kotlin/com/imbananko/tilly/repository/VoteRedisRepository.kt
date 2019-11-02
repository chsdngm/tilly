package com.imbananko.tilly.repository

import com.imbananko.tilly.model.VoteEntity
import com.imbananko.tilly.model.VoteValue
import org.springframework.data.redis.core.HashOperations
import org.springframework.stereotype.Repository
import javax.annotation.Resource

@Repository
class VoteRedisRepository {
  @Resource(name = "stringRedisTemplate")
  private lateinit var hashOperations: HashOperations<String, String, String>

  fun processVote(vote: VoteEntity): HashMap<VoteValue, Int> {
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
}
package data

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UserRepositoryTest {

    @BeforeTest
    fun setUp() {
        // A fresh, uniquely-named in-memory H2 database per test avoids any state bleeding
        // between tests without needing a real Postgres instance (e.g. via Docker).
        Database.connect("jdbc:h2:mem:test_${System.nanoTime()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction {
            SchemaUtils.create(UserTable)
        }
    }

    @Test
    fun `signing up a new user registers them`() {
        val outcome = UserRepository.addUser("discord-1", "account-1", "channel-1", "Alice")

        assertEquals(UserRepository.SignupOutcome.REGISTERED, outcome)
        val stored = UserRepository.selectUserByDiscordID("discord-1")
        assertNotNull(stored)
        assertEquals("account-1", stored.accountId)
        assertEquals("channel-1", stored.channelId)
    }

    @Test
    fun `signing up twice with the same discord id is rejected, not overwritten`() {
        UserRepository.addUser("discord-1", "account-1", "channel-1", "Alice")

        val outcome = UserRepository.addUser("discord-1", "account-2", "channel-2", "Alice")

        assertEquals(UserRepository.SignupOutcome.ALREADY_REGISTERED, outcome)
        // original registration must be untouched
        assertEquals("account-1", UserRepository.selectUserByDiscordID("discord-1")?.accountId)
    }

    @Test
    fun `signing up with an account id already tracked by someone else is rejected`() {
        UserRepository.addUser("discord-1", "account-1", "channel-1", "Alice")

        val outcome = UserRepository.addUser("discord-2", "account-1", "channel-2", "Bob")

        assertEquals(UserRepository.SignupOutcome.ACCOUNT_ID_IN_USE, outcome)
        assertNull(UserRepository.selectUserByDiscordID("discord-2"))
    }

    @Test
    fun `unsubscribe removes the tracked user`() {
        UserRepository.addUser("discord-1", "account-1", "channel-1", "Alice")

        UserRepository.removeUser("discord-1")

        assertNull(UserRepository.selectUserByDiscordID("discord-1"))
    }

    @Test
    fun `updateLastMatch persists the latest match id`() {
        UserRepository.addUser("discord-1", "account-1", "channel-1", "Alice")

        UserRepository.updateLastMatch("account-1", "999")

        assertEquals("999", UserRepository.selectUserByDiscordID("discord-1")?.lastMatchId)
    }

    @Test
    fun `getAllUsers returns every tracked user`() {
        UserRepository.addUser("discord-1", "account-1", "channel-1", "Alice")
        UserRepository.addUser("discord-2", "account-2", "channel-2", "Bob")

        val all = UserRepository.getAllUsers()

        assertEquals(2, all.size)
        assertEquals(setOf("discord-1", "discord-2"), all.map { it.discordId }.toSet())
    }
}

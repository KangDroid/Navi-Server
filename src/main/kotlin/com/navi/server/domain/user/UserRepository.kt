package com.navi.server.domain.user

import com.mongodb.client.result.UpdateResult
import com.navi.server.domain.FileObject
import com.navi.server.error.exception.NotFoundException
import com.navi.server.error.exception.UnknownErrorException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.*
import org.springframework.data.mongodb.core.find
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository

@Repository
class UserTemplateRepository {
    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    private val objectIdField: String = "id"
    private val userIdField: String = "userId"
    private val userNameField: String = "userName"
    private val userEmailField: String = "userEmail"
    private val fileListField: String = "fileList"
    private val fileListTokenField: String = "token"
    private val fileListPrevTokenField: String = "prevToken"

    /**
     * clearAll() : Remove Every entity/collection in DB, including file information
     */
    fun clearAll() {
        mongoTemplate.remove(Query(), User::class.java)
    }

    /**
     * save(user: User): User
     * Save/Update user and return saved/updated user.
     */
    fun save(user: User): User {
        return mongoTemplate.save(user)
    }

    /**
     * findAllUserOnly(): List<User>
     * Find All user, but excluding fileList
     *
     * The reason we exclude fileList is to reduce memory usage if fileList is really large.
     * Therefore, the list it return will have just empty list of fileObject.
     */
    fun findAllUserOnly(): List<User> {
        val findQuery: Query =  Query()
        findQuery.fields().exclude(fileListField)

        return mongoTemplate.find(findQuery, User::class.java)
    }

    /**
     * findAllFileList(inputUserId: String): List<FileObject>
     * Find all file list based on inputUserId
     */
    fun findAllFileList(inputUserId: String): List<FileObject> {
        val user: User = findByUserId(inputUserId) ?: run {
            throw NotFoundException("Cannot find userid with: $inputUserId")
        }

        return user.fileList.toList()
    }

    /**
     * findAll(): List<User>
     * unlike findAllUserOnly, it returns pure-collection to object. Including fileList.
     *
     * Warning:
     * If each user's fileList is LARGE, avoid using this.
     * To find inside file list, use findAllByPrevToken / findAllByToken instead.
     */
    fun findAll(): List<User> {
        val findQuery: Query =  Query()
        return mongoTemplate.find(findQuery, User::class.java)
    }

    /**
     * Inner function to search inside each user's fileList document.
     * function results vary, depdends on inputSerachKey && inputSearchValue.
     */
    private fun innerFileListSearch(
        inputUserId: String,
        inputSearchKey: String,
        inputSearchValue: String
    ): AggregationResults<User> {
        // Match user name [Filter userid first]
        val userIdMatchCriteria: Criteria = Criteria.where(userIdField).`is`(inputUserId)
        val matchOperation: MatchOperation = Aggregation.match(userIdMatchCriteria)

        // Unwind
        val unwindOperation: UnwindOperation = Aggregation.unwind(fileListField)

        // Match Token [Filter token]
        val fileTokenMatchCriteria: Criteria = Criteria.where(inputSearchKey).`is`(inputSearchValue)
        val fileTokenMatchOperation: MatchOperation = Aggregation.match(fileTokenMatchCriteria)

        // Group
        val groupOperation: GroupOperation = Aggregation.group(objectIdField)
            .push(
                fileListField
            ).`as`(fileListField)

        return mongoTemplate.aggregate(
            Aggregation.newAggregation(
                matchOperation,
                unwindOperation,
                fileTokenMatchOperation,
                groupOperation
            ),
            User::class.java,
            User::class.java
        )
    }

    /**
     * findAllByPrevToken(inputUserId: String, inputPrevToken: String): List<FileObject>?
     * Search inputUserId's fileList where fileList.prevToken = inputPrevToken
     * Also, it only returns object corresponding search query.
     *
     * Warning:
     * Do not attempt to re-save this functions result to db. Re-Saving will blow up user's other fileList.
     */
    fun findAllByPrevToken(inputUserId: String, inputPrevToken: String): List<FileObject> {
        val results: AggregationResults<User> = innerFileListSearch(inputUserId, "$fileListField.$fileListPrevTokenField", inputPrevToken)
        return if (results.mappedResults.isEmpty()) {
            listOf()
        } else {
            results.mappedResults[0].fileList
        }
    }

    /**
     * findByToken(inputUserId: String, inputToken: String): FileObject?
     * Mostly same as findAllByPrevToken, but the query is token = inputToken.
     *
     * Warning:
     * As Same as findAllByPrevToken, do not attempt to re-save this function result to db.
     */
    fun findByToken(inputUserId: String, inputToken: String): FileObject {
        val results: AggregationResults<User> = innerFileListSearch(inputUserId, "$fileListField.$fileListTokenField", inputToken)
        if (results.mappedResults.size != 1 ) {
            throw NotFoundException("""
                Input userid was: $inputUserId, requested file token was: $inputToken.
                Perhaps invalid user or requested with non-existence token?
            """.trimIndent())
        }

        if (results.mappedResults[0].fileList.size < 1) {
            throw NotFoundException("""
                Requested file was not found!
            """.trimIndent())
        }

        if (results.mappedResults[0].fileList.size > 1) {
            throw UnknownErrorException("""
                Mapped fileList result should be exactly 1, but somehow its size is more than 1.
            """.trimIndent())
        }

        return results.mappedResults[0].fileList[0]
    }

    /**
     * findByUserName(inputUserName: String): User?
     * Returns user document[full document] where user name = inputUserName.
     */
    fun findByUserName(inputUserName: String): User {
        val findNameQuery: Query = Query()
        findNameQuery.addCriteria(
            Criteria.where(userNameField).`is`(inputUserName)
        )

        lateinit var user: User
        runCatching {
            mongoTemplate.findOne(findNameQuery, User::class.java)
        }.onSuccess {
            if (it != null) {
                user = it
            } else {
                throw NotFoundException("Cannot find user with username: $inputUserName")
            }
        }

        return user
    }

    /**
     * findByUserId(inputUserId: String): User?
     * Returns user document[full document] where user userId = inputUserId.
     */
    fun findByUserId(inputUserId: String): User {
        val findNameQuery: Query = Query()
        findNameQuery.addCriteria(
            Criteria.where(userIdField).`is`(inputUserId)
        )

        lateinit var user: User
        runCatching {
            mongoTemplate.findOne(findNameQuery, User::class.java)
        }.onSuccess {
            if (it != null) {
                user = it
            } else {
                throw NotFoundException("Cannot find user with userid: $inputUserId")
            }
        }

        return user
    }

    /**
     * findByUserEmail(inputUserEmail: String): User?
     * Returns user document[full document] where user email = inputUserEmail.
     */
    fun findByUserEmail(inputUserEmail: String): User {
        val findEmailQuery: Query = Query()
        findEmailQuery.addCriteria(
            Criteria.where(userEmailField).`is`(inputUserEmail)
        )

        lateinit var user: User
        runCatching {
            mongoTemplate.findOne(findEmailQuery, User::class.java)
        }.onSuccess {
            if (it != null) {
                user = it
            } else {
                throw NotFoundException("Cannot find user with useremail: $inputUserEmail")
            }
        }

        return user
    }

    /**
     * deleteByToken(inputUserId: String, inputToken: String): UpdateResult
     * Delete inputUserId's specific fileList, where fileList.token = inputToken.
     */
    fun deleteByToken(inputUserId: String, inputToken: String): UpdateResult {
        val updateQuery: Query = Query().apply {
            addCriteria(
                Criteria.where(userIdField).`is`(inputUserId)
            )
        }

        val update: Update = Update().pull(
            fileListField,
            Query.query(Criteria.where(fileListTokenField).`is`(inputToken))
        )

        return mongoTemplate.updateMulti(updateQuery, update, User::class.java)
    }
}
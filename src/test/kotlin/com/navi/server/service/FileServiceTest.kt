package com.navi.server.service

import com.navi.server.component.FileConfigurationComponent
import com.navi.server.component.FilePathResolver
import com.navi.server.domain.FileObject
import com.navi.server.domain.user.User
import com.navi.server.domain.user.UserTemplateRepository
import com.navi.server.dto.FileResponseDTO
import com.navi.server.error.exception.NotFoundException
import com.navi.server.security.JWTTokenProvider
import org.apache.cxf.jaxrs.ext.StreamingResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.junit4.SpringRunner
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.BufferedReader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@RunWith(SpringRunner::class)
@SpringBootTest
class FileServiceTest {
    @Autowired
    private lateinit var fileService: FileService

    @Autowired
    private lateinit var userTemplateRepository: UserTemplateRepository

    @Autowired
    private lateinit var fileConfigurationComponent: FileConfigurationComponent

    @Autowired
    private lateinit var jwtTokenProvider: JWTTokenProvider

    @Autowired
    private lateinit var filePathResolver: FilePathResolver

    private lateinit var trashRootObject: File

    private val mockUser: User = User(
        userId = "kangdroid",
        userName = "Jason Kang",
        userPassword = ""
    )

    private fun registerAndLogin(): String {
        // Init User Structure
        fileConfigurationComponent.initNewUserStructure(mockUser)

        // Token
        return jwtTokenProvider.createToken(mockUser.userId, mockUser.roles.toList())
    }

    @Before
    fun initEnvironment() {
        fileConfigurationComponent.serverRoot = File(System.getProperty("java.io.tmpdir"), "naviServerTesting").absolutePath
        // Create trash directory
        trashRootObject = File(fileConfigurationComponent.serverRoot)
        trashRootObject.mkdir()
    }

    @After
    fun destroyEnvironment() {
        if (trashRootObject.exists()) {
            trashRootObject.deleteRecursively()
        }
        userTemplateRepository.clearAll()
    }

    /*
    @Test
    fun is_findRootToken_throws_40x_root_token() {
        val loginToken: String = registerAndLogin()

        runCatching {
            fileService.findRootToken(loginToken)
        }.onFailure {
            assertThat(it is NotFoundException).isEqualTo(true)
        }.onSuccess {
            fail("INIT did not proceeded, but somehow it succeed?")
        }
    }*/

    @Test
    fun is_findRootToken_throws_404_unknown_token() {
        runCatching {
            fileService.findRootToken("what_token")
        }.onSuccess {
            fail("Wrong token passed but this test passed somehow.")
        }.onFailure {
            assertThat(it is NotFoundException).isEqualTo(true)
            assertThat(it.message).isEqualTo("Userid is NOT Found!")
        }
    }

    @Test
    fun is_findRootToken_OK() {
        val loginToken: String = registerAndLogin()

        runCatching {
            fileService.findRootToken(loginToken)
        }.onSuccess {
            assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(it.hasBody()).isEqualTo(true)
            assertThat(it.body).isNotEqualTo("")
        }.onFailure {
            println(it.stackTraceToString())
            fail("This test should return OK because we saved and init-ed its structure, but somehow it failed!")
        }
    }

    // findAllDesc Test
    @Test
    fun is_findAllDesc_404_unknown_token() {
        runCatching {
            fileService.findAllDesc("test")
        }.onSuccess {
            fail("Wrong token passed but this test passed somehow")
        }.onFailure {
            assertThat(it is NotFoundException).isEqualTo(true)
            assertThat(it.message).isEqualTo("Userid is NOT Found!")
        }
    }

    @Test
    fun is_findAllDesc_OK() {
        val loginToken: String = registerAndLogin()

        runCatching {
            fileService.findAllDesc(loginToken)
        }.onSuccess {
            assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(it.hasBody()).isEqualTo(true)
            assertThat(it.body!!.size).isEqualTo(1L)
        }.onFailure {
            println(it.stackTraceToString())
            fail("This test should return OK because we saved and init-ed its structure, but somehow it failed!")
        }
    }

    // findInsideFiles test
    @Test
    fun is_findInsideFiles_404_no_file() {
        val loginToken: String = registerAndLogin()

        runCatching {
            fileService.findInsideFiles(loginToken, "somewhat_wrong_token")
        }.onSuccess {
            fail("PrevToken is fake, but somehow it succeed!")
        }.onFailure {
            assertThat(it is NotFoundException).isEqualTo(true)
        }
    }

    @Test
    fun is_findInsideFiles_ok() {
        val loginToken: String = registerAndLogin()
        File(fileConfigurationComponent.serverRoot, mockUser.userId).mkdir()
        val path: Path = Paths.get(fileConfigurationComponent.serverRoot, mockUser.userId, "test.txt")
        val fileObject: File = path.toFile()
        fileObject.writeText("test")
        fileConfigurationComponent.initStructure()

        runCatching {
            fileService.findInsideFiles(loginToken, fileService.getSHA256("/"))
        }.onFailure {
            println(it.stackTraceToString())
            fail("This test should be succeed!")
        }.onSuccess {
            assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(it.hasBody()).isEqualTo(true)
            assertThat(it.body!!.size).isEqualTo(1L)
            assertThat(it.body!![0].fileName).isEqualTo("/test.txt")
        }
    }

    @Test
    fun is_findInsideFiles_empty_ok() {
        val loginToken: String = registerAndLogin()

        runCatching {
            fileService.findInsideFiles(loginToken, fileService.getSHA256("/"))
        }.onFailure {
            println(it.stackTraceToString())
            fail("This test should be succeed!")
        }.onSuccess {
            assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(it.hasBody()).isEqualTo(true)
        }
    }

    // convertFileNameToFullPath test
    @Test
    fun is_convertFileNameToFullPath_works_well() {
        val userFilePath: Path = Paths.get("kdr", "test", "test.txt")
        val workedString: String = filePathResolver.convertFileNameToFullPath("KangDroid", userFilePath.toString())

        val expectedPath: Path = Paths.get(fileConfigurationComponent.serverRoot, "KangDroid", userFilePath.toString())
        assertThat(workedString).isEqualTo(expectedPath.toString())
    }

    // fileUpload Test

    @Test
    fun is_fileUpload_throws_IOException() {
        // Let
        val loginToken: String = registerAndLogin()

        val multipartFile2 = MockMultipartFile(
            "uploadFileName", "".toByteArray()
        )
        runCatching {
            fileService.fileUpload(loginToken, fileService.getSHA256("/"), multipartFile2)
        }.onSuccess {
            fail("This Should be failed,....")
        }.onFailure {
            assertThat(it.message).isEqualTo("File IO Exception")
        }
    }

    @Test
    fun is_fileUpload_working_well() {
        // Create Server Root Structure
        val loginToken: String = registerAndLogin()

        // make uploadFile
        val uploadFileName: String = "uploadTest-service.txt"
        val uploadFileContent: ByteArray = "file upload test file!".toByteArray()
        val multipartFile: MockMultipartFile = MockMultipartFile(
            uploadFileName, uploadFileName, "text/plain", uploadFileContent
        )

        // file upload
        val uploadFolderToken = fileService.getSHA256("/") // Upload to user's root
        fileService.fileUpload(
            userToken = loginToken,
            uploadFolderToken = uploadFolderToken,
            files = multipartFile
        )

        // Whether uploaded text file is actually exists
        val userId: String = jwtTokenProvider.getUserPk(loginToken)
        val uploadedFileObject: File =
            Paths.get(fileConfigurationComponent.serverRoot, userId, uploadFileName).toFile()
        assertThat(uploadedFileObject.exists()).isEqualTo(true)

        // Now Check DB
        val user: User = userTemplateRepository.findByUserId(userId)
        val fileList: MutableList<FileObject> = user.fileList
        assertThat(fileList.size).isEqualTo(2L)
        assertThat(fileList[1].fileName).isEqualTo("/$uploadFileName")
    }

    // getFileObjectByUserName test
    @Test
    fun is_getFileObjectByUserName_returns_404_wrong_user() {
        runCatching {
            fileService.getFileObjectByUserId("wrong_user", fileService.getSHA256("/"))
        }.onSuccess {
            fail("Wrong user specified but somehow function succeed?")
        }.onFailure {
            assertThat(it is NotFoundException).isEqualTo(true)
        }
    }

    @Test
    fun is_getFileObjectByUserName_returns_fileObject() {
        // Let
        val fileObject: FileObject = FileObject(
            fileName = "test_file",
            fileType = "test_type",
            mimeType = "mime_type",
            token = "test_token",
            prevToken = "prev_token",
            lastModifiedTime = System.currentTimeMillis(),
            fileCreatedDate = "date",
            fileSize = "5m"
        )

        val user: User = User(
            userName = "KangDroid",
            userPassword = "userpassword",
            roles = setOf("ROLE_ADMIN"),
            fileList = mutableListOf(fileObject)
        )
        userTemplateRepository.save(user)

        val responseFileObject: FileObject = runCatching {
            fileService.getFileObjectByUserId(user.userId, fileObject.token)
        }.getOrElse {
            fail("All things are set-up, so it should not fail!")
        }

        assertThat(responseFileObject.fileName).isEqualTo(fileObject.fileName)
        assertThat(responseFileObject.fileType).isEqualTo(fileObject.fileType)
        assertThat(responseFileObject.mimeType).isEqualTo(fileObject.mimeType)
        assertThat(responseFileObject.token).isEqualTo(fileObject.token)
        assertThat(responseFileObject.prevToken).isEqualTo(fileObject.prevToken)
    }

    // fileDownload test
    @Test
    fun is_fileDownload_returns_NotFoundException_no_file() {
        // Let
        val fileObject: FileObject = FileObject(
            fileName = "test_file",
            fileType = "test_type",
            mimeType = "mime_type",
            token = "test_token",
            prevToken = "prev_token",
            lastModifiedTime = System.currentTimeMillis(),
            fileCreatedDate = "date",
            fileSize = "5m"
        )

        val loginToken: String = registerAndLogin()
        val user: User = userTemplateRepository.findByUserId(mockUser.userId)
        user.fileList.add(fileObject)
        userTemplateRepository.save(user)

        runCatching {
            fileService.fileDownload(loginToken, fileObject.token)
        }.onSuccess {
            fail("This should return 404 because we do not have actual file.")
        }.onFailure {
            assertThat(it is NotFoundException).isEqualTo(true)
        }
    }

    @Test
    fun is_fileDownload_returns_OK() {
        // Let
        val fileObject: FileObject = FileObject(
            fileName = "test_file",
            fileType = "test_type",
            mimeType = "mime_type",
            token = "test_token",
            prevToken = "prev_token",
            lastModifiedTime = System.currentTimeMillis(),
            fileCreatedDate = "date",
            fileSize = "5m"
        )

        val loginToken: String = registerAndLogin()
        val user: User = userTemplateRepository.findByUserId(mockUser.userId)
        user.fileList.add(fileObject)
        userTemplateRepository.save(user)
        // Write some texts
        Paths.get(fileConfigurationComponent.serverRoot, mockUser.userId).toFile().mkdirs()
        val file: File = Paths.get(fileConfigurationComponent.serverRoot, mockUser.userId, fileObject.fileName).toFile()
        file.writeText("Test!")

        runCatching {
            fileService.fileDownload(loginToken, fileObject.token)
        }.onFailure {
            fail("This should not fail! : ${it.stackTraceToString()}")
        }
    }


    /* create new folder Test */

    @Test
    fun is_createNewFolder_working_well() {
        val newFolderName: String = "je"

        // Create Server Root Structure
        val loginToken: String = registerAndLogin()

        // create new folder to root directory
        val parentFolderToken = fileService.getSHA256("/")
        fileService.createNewFolder(
            userToken = loginToken,
            parentFolderToken = parentFolderToken,
            newFolderName = newFolderName
        )

        // Whether uploaded text file is actually exists
        val userId: String = jwtTokenProvider.getUserPk(loginToken)
        val uploadedFileObject: File =
            Paths.get(fileConfigurationComponent.serverRoot, userId, newFolderName).toFile()
        assertThat(uploadedFileObject.exists()).isEqualTo(true)

        // Now Check DB
        val user: User = userTemplateRepository.findByUserId(userId)
        val fileList: MutableList<FileObject> = user.fileList
        assertThat(fileList.size).isEqualTo(2L)
        assertThat(fileList[1].fileName).isEqualTo("/$newFolderName")
        assertThat(fileList[1].fileType).isEqualTo("Folder")
    }

    @Test
    fun is_createNewFolder_throws_ConflictException() {
        val newFolderName: String = "je"

        // Create Server Root Structure
        val loginToken: String = registerAndLogin()

        val parentFolderToken = fileService.getSHA256("/")

        // Create new folder to root directory named $newFolderName
        fileService.createNewFolder(
            userToken = loginToken,
            parentFolderToken = parentFolderToken,
            newFolderName = newFolderName
        )

        // Try to create new folder with the same name $newFolderName
        // This will throw ConflictException
        runCatching {
            fileService.createNewFolder(
                userToken = loginToken,
                parentFolderToken = parentFolderToken,
                newFolderName = newFolderName
            )
        }.onSuccess {
            fail("This Should be failed,....")
        }.onFailure {
            assertThat(it.message).isEqualTo("Folder $newFolderName already exists!")
        }
    }

    @Test
    fun isConvertingCorrect() {
        val testFileSizeMib: Long = 1024 * 1024 * 2 // 2 Mib
        val testFileSizeKib: Long = 1024 * 4 // 4.0Kib
        val testFileSizeB: Long = 800 //800B
        val testFileSizeZero: Long = 0
        assertThat(fileService.convertSize(testFileSizeMib)).isEqualTo("2.0MiB")
        assertThat(fileService.convertSize(testFileSizeKib)).isEqualTo("4.0KiB")
        assertThat(fileService.convertSize(testFileSizeB)).isEqualTo("800B")
        assertThat(fileService.convertSize(testFileSizeZero)).isEqualTo("0B")
    }

    @Test
    fun isGettingSHA256WorksWell() {
        fail("INIT did not proceeded, but somehow it succeed?")
        val targetPlainText: String = "TestingKDR"
        val hashedString: String = fileService.getSHA256(targetPlainText)

        assertThat(hashedString).isNotEqualTo(null)
        assertThat(hashedString).isNotEqualTo("")
        assertThat(targetPlainText).isNotEqualTo(hashedString)
    }
}

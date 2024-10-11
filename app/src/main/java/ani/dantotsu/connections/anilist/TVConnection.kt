package ani.dantotsu.connections.anilist

import android.app.Activity
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import android.util.Log
import ani.dantotsu.R
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.startMainActivity
import ani.dantotsu.toast
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

object TVConnection {

    private const val PORT = 1508
    private const val SERVICE_TYPE = "_http._tcp."
    private const val SERVICE_NAME = "DantotsuTV"

    private var nsdManager: NsdManager? = null
    private var isListening = false
    private var listeningThread: Thread? = null
    private var serverSocket: ServerSocket? = null

    // Store the discoveryListener so we can reference it
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /*** Shared code between TV and Phone ***/

    // Function to generate a random code
    fun generateRandomCode(): String {
        val codeLength = 3
        val allowedChars = ('1'..'9')
        return (1..codeLength)
            .map { allowedChars.random() }
            .joinToString("")
    }

    // Starts the service and begins listening (TV side)
    fun startService(context: Context) {
        Log.d("TVConnection", "Starting NSD service on TV")
        // On TV: Register NSD service and start listening for connections
        registerService(context)
        listenForConnections(context)
    }

    // Stops the service and listening (TV side)
    fun stopService() {
        isListening = false
        unregisterService()
        // Close the server socket
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        serverSocket = null
        listeningThread?.interrupt()
        listeningThread = null
    }

    /*** NSD Service Registration and Discovery ***/

    // Registers the NSD service (TV side)
    private fun registerService(context: Context) {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = PORT
        }

        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            Log.d("TVConnection", "Service registered: ${serviceInfo.serviceName}")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e("TVConnection", "Service registration failed: $errorCode")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            Log.d("TVConnection", "Service unregistered: ${serviceInfo.serviceName}")
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e("TVConnection", "Service unregistration failed: $errorCode")
        }
    }

    // Unregisters the NSD service (TV side)
    private fun unregisterService() {
        nsdManager?.unregisterService(registrationListener)
    }

    // Discovers the TV service on the network (Phone side)
    private fun discoverService(context: Context, onServiceFound: (InetAddress?, Int) -> Unit) {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d("TVConnection", "Service discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d("TVConnection", "Service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceName == SERVICE_NAME && serviceInfo.serviceType == SERVICE_TYPE) {
                    // Resolve the service to get host and port
                    nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e("TVConnection", "Service resolve failed: $errorCode")
                            onServiceFound(null, -1)
                        }

                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            val host = resolvedServiceInfo.host
                            val port = resolvedServiceInfo.port
                            Log.d("TVConnection", "Service resolved: $host:$port")
                            onServiceFound(host, port)
                            nsdManager?.stopServiceDiscovery(discoveryListener)
                        }
                    })
                } else {
                    Log.d("TVConnection", "Discovered service does not match")
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d("TVConnection", "Service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d("TVConnection", "Service discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("TVConnection", "Service discovery start failed: $errorCode")
                nsdManager?.stopServiceDiscovery(this)
                onServiceFound(null, -1)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("TVConnection", "Service discovery stop failed: $errorCode")
                nsdManager?.stopServiceDiscovery(this)
                onServiceFound(null, -1)
            }
        }

        nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    /*** Connection Handling ***/

    // Listens for incoming connections (TV side)
    private fun listenForConnections(context: Context) {
        listeningThread = thread(start = true) {
            try {
                isListening = true
                Log.d("TVConnection", "Listening for connections...")

                serverSocket = ServerSocket(PORT)

                while (isListening) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        val streamReader = BufferedReader(InputStreamReader(socket.getInputStream()))

                        val receivedCode = streamReader.readLine()
                        val ivBase64 = streamReader.readLine()
                        val encryptedDataBase64 = streamReader.readLine()

                        // Verify the code
                        val expectedCode = getSavedGeneratedCode(context)
                        if (receivedCode == expectedCode) {
                            // Derive the key
                            val key = deriveKey(receivedCode)

                            // Decrypt the data
                            val decryptedData = decryptData(encryptedDataBase64, ivBase64, key)

                            // Deserialize the data
                            val gson = GsonBuilder().create()
                            val type = object : TypeToken<Map<String, Any>>() {}.type
                            val dataMap: Map<String, Any> = gson.fromJson(decryptedData, type)

                            // Save the data
                            saveData(context, dataMap)

                            // Notify success
                            (context as Activity).runOnUiThread {
                                toast(R.string.tv_login_success)
                                // Restart the main activity or update UI
                                startMainActivity(context)
                            }

                            // Stop listening after successful data reception
                            isListening = false
                            socket.close()
                            break
                        } else {
                            // Invalid code
                            (context as Activity).runOnUiThread {
                                toast(R.string.tv_login_invalid_code)
                            }
                            Log.d("TVConnection", "Invalid code received")
                            socket.close()
                        }
                    } catch (e: SocketException) {
                        // Socket was closed, exit loop
                        Log.d("TVConnection", "Server socket closed")
                        break
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Log.e("TVConnection", "Error in listenForConnections", e)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("TVConnection", "Error in listenForConnections", e)
            } finally {
                isListening = false
                try {
                    serverSocket?.close()
                } catch (e: Exception) {
                    // Ignore
                }
                serverSocket = null
            }
        }
    }

    // Function to send data to TV (phone side)
    fun sendDataToTV(context: Context, code: String) {
        Log.d("TVConnection", "sendDataToTV called with code: $code")
        try {
            discoverService(context) { host, port ->
                if (host != null && port != -1) {
                    (context as Activity).runOnUiThread {
                        toast(R.string.tv_found)
                    }
                    Log.d("TVConnection", "TV service found at $host:$port")

                    val dataMap = mutableMapOf<String, Any>()

                    // Collect data from PrefManager
                    val anilistToken = PrefManager.getVal(PrefName.AnilistToken, "") ?: ""
                    if (anilistToken != "") {
                        dataMap["AnilistToken"] = anilistToken
                    }

                    val anilistUserName = PrefManager.getVal(PrefName.AnilistUserName, "") ?: ""
                    if (anilistUserName != "") {
                        dataMap["AnilistUserName"] = anilistUserName
                    }

                    val anilistUserId = PrefManager.getVal(PrefName.AnilistUserId, "") ?: ""
                    if (anilistUserId != "") {
                        dataMap["AnilistUserId"] = anilistUserId
                    }

                    val malToken = PrefManager.getVal(PrefName.MALToken, "") ?: ""
                    if (malToken != "") {
                        dataMap["MALToken"] = malToken
                    }

                    val malCodeChallenge = PrefManager.getVal(PrefName.MALCodeChallenge, "") ?: ""
                    if (malCodeChallenge != "") {
                        dataMap["MALCodeChallenge"] = malCodeChallenge
                    }

                    val discordToken = PrefManager.getVal(PrefName.DiscordToken, "") ?: ""
                    if (discordToken != "") {
                        dataMap["DiscordToken"] = discordToken
                    }

                    val discordId = PrefManager.getVal(PrefName.DiscordId, "") ?: ""
                    if (discordId != "") {
                        dataMap["DiscordId"] = discordId
                    }

                    val discordUserName = PrefManager.getVal(PrefName.DiscordUserName, "") ?: ""
                    if (discordUserName != "") {
                        dataMap["DiscordUserName"] = discordUserName
                    }

                    val discordAvatar = PrefManager.getVal(PrefName.DiscordAvatar, "") ?: ""
                    if (discordAvatar != "") {
                        dataMap["DiscordAvatar"] = discordAvatar
                    }

                    val animeExtensionRepos = PrefManager.getVal(PrefName.AnimeExtensionRepos, setOf<String>())
                    if (animeExtensionRepos.isNotEmpty()) {
                        dataMap["AnimeExtensionRepos"] = animeExtensionRepos
                    }

                    val mangaExtensionRepos = PrefManager.getVal(PrefName.MangaExtensionRepos, setOf<String>())
                    if (mangaExtensionRepos.isNotEmpty()) {
                        dataMap["MangaExtensionRepos"] = mangaExtensionRepos
                    }

                    val useSourceTheme = PrefManager.getVal(PrefName.UseSourceTheme, false)
                    if (useSourceTheme) {
                        dataMap["UseSourceTheme"] = useSourceTheme
                    }

                    val useMaterialYou = PrefManager.getVal(PrefName.UseMaterialYou, false)
                    if (useMaterialYou) {
                        dataMap["UseMaterialYou"] = useMaterialYou
                    }

                    val theme = PrefManager.getVal(PrefName.Theme, "") ?: ""
                    if (theme != "") {
                        dataMap["Theme"] = theme
                    }

                    // Serialize the data to JSON
                    val gson = GsonBuilder().create()
                    val jsonData = gson.toJson(dataMap)

                    // Send the data to the TV
                    thread(start = true) {
                        try {
                            Log.d("TVConnection", "Attempting to connect to TV at $host:$port")

                            // Derive the key and encrypt the data
                            val key = deriveKey(code)
                            val (ivBase64, encryptedDataBase64) = encryptData(jsonData, key)

                            val socket = Socket(host, port)
                            val streamWriter = PrintWriter(socket.getOutputStream(), true)
                            streamWriter.println(code)
                            streamWriter.println(ivBase64)
                            streamWriter.println(encryptedDataBase64)

                            streamWriter.flush()
                            socket.close()

                            Log.d("TVConnection", "Data sent successfully")
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Log.e("TVConnection", "Error sending data to TV", e)
                        }
                    }

                } else {
                    (context as Activity).runOnUiThread {
                        toast(R.string.tv_not_found)
                        Log.d("TVConnection", "TV not found on the network")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("TVConnection", "Exception in sendDataToTV", e)
            (context as Activity).runOnUiThread {
                toast("Error: ${e.message}")
            }
        }
    }

    /*** Utility Methods ***/

    // Saves the generated code (TV side)
    fun saveGeneratedCode(context: Context, code: String) {
        val sharedPref = context.getSharedPreferences("TV_LOGIN", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString("generated_code", code)
            apply()
        }
    }

    // Retrieves the saved generated code (TV side)
    private fun getSavedGeneratedCode(context: Context): String? {
        val sharedPref = context.getSharedPreferences("TV_LOGIN", Context.MODE_PRIVATE)
        return sharedPref.getString("generated_code", null)
    }

    // Saves the data received (TV side)
    private fun saveData(context: Context, dataMap: Map<String, Any>) {
        dataMap.forEach { (key, value) ->
            when (key) {
                "AnilistToken" -> PrefManager.setVal(PrefName.AnilistToken, value as String)
                "AnilistUserName" -> PrefManager.setVal(PrefName.AnilistUserName, value as String)
                "AnilistUserId" -> PrefManager.setVal(PrefName.AnilistUserId, value as String)
                "MALToken" -> PrefManager.setVal(PrefName.MALToken, value as MAL.ResponseToken) //todo: This doesnt log in on tv, idk why, dont care enough to waste time on it atm
                "MALCodeChallenge" -> PrefManager.setVal(PrefName.MALCodeChallenge, value as String)
                "DiscordToken" -> PrefManager.setVal(PrefName.DiscordToken, value as String)
                "DiscordId" -> PrefManager.setVal(PrefName.DiscordId, value as String)
                "DiscordUserName" -> PrefManager.setVal(PrefName.DiscordUserName, value as String)
                "DiscordAvatar" -> PrefManager.setVal(PrefName.DiscordAvatar, value as String)
                "AnimeExtensionRepos" -> {
                    val set = (value as List<String>).toSet()
                    PrefManager.setVal(PrefName.AnimeExtensionRepos, set)
                }
                "MangaExtensionRepos" -> {
                    val set = (value as List<String>).toSet()
                    PrefManager.setVal(PrefName.MangaExtensionRepos, set)
                }
                "UseSourceTheme" -> PrefManager.setVal(PrefName.UseSourceTheme, value as Boolean)
                "UseMaterialYou" -> PrefManager.setVal(PrefName.UseMaterialYou, value as Boolean)
                "Theme" -> PrefManager.setVal(PrefName.Theme, value as String)
                else -> Log.d("TVConnection", "Unknown key received: $key")
            }
        }
        // Restart the main activity
        startMainActivity(context as Activity)  //TODO: doesnt actually update everything, app needs to be manually restarted to read repos???
    }

    /*** Encryption and Decryption Methods ***/

    private const val SALT = "AllOfThisBecauseICouldNotBeBotheredToUseSSLSocketsSueMe"

    // Function to derive the key from the code
    private fun deriveKey(code: String): ByteArray {
        val iterationCount = 10000
        val keyLength = 256
        val saltBytes = SALT.toByteArray(Charsets.UTF_8)
        val codeChars = code.toCharArray()

        val keySpec = PBEKeySpec(codeChars, saltBytes, iterationCount, keyLength)
        val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val secretKey = keyFactory.generateSecret(keySpec)
        return secretKey.encoded
    }

    // Function to encrypt the data
    private fun encryptData(data: String, key: ByteArray): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKeySpec = SecretKeySpec(key, "AES")

        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)
        val ivSpec = IvParameterSpec(iv)

        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec)
        val encryptedBytes = cipher.doFinal(data.toByteArray(Charsets.UTF_8))

        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val encryptedDataBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)

        return Pair(ivBase64, encryptedDataBase64)
    }

    // Function to decrypt the data
    private fun decryptData(encryptedDataBase64: String, ivBase64: String, key: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKeySpec = SecretKeySpec(key, "AES")

        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val ivSpec = IvParameterSpec(iv)

        val encryptedBytes = Base64.decode(encryptedDataBase64, Base64.NO_WRAP)

        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec)
        val decryptedBytes = cipher.doFinal(encryptedBytes)

        return String(decryptedBytes, Charsets.UTF_8)
    }
}

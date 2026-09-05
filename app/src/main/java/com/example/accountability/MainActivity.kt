package com.example.accountability

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.firstOrNull

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val calendarGranted = permissions[Manifest.permission.READ_CALENDAR] ?: false
        var notificationsGranted = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationsGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        }

        if (calendarGranted && notificationsGranted) {
            Toast.makeText(this, "Permissions granted. Ready to be judged.", Toast.LENGTH_SHORT).show()
            scheduleWorker()
        } else {
            Toast.makeText(this, "Permissions required for full functionality.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AccountabilityTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onRequestPermissions = { requestPermissions() }

                    )
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.READ_CALENDAR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            Toast.makeText(this, "Permissions already granted.", Toast.LENGTH_SHORT).show()
            scheduleWorker()
        }
    }

    private fun scheduleWorker() {
        val workRequest = PeriodicWorkRequestBuilder<AccountabilityWorker>(15, TimeUnit.MINUTES)
            .build()

        val workManager = WorkManager.getInstance(this)
        workManager.enqueueUniquePeriodicWork(
            "AccountabilityCheck",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )

        // Run one check immediately so the user (and widget) get feedback now
        // instead of waiting up to 15 minutes for the first periodic run.
        workManager.enqueue(OneTimeWorkRequestBuilder<AccountabilityWorker>().build())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onRequestPermissions: () -> Unit

) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val userProfileRepo = remember { UserProfileRepository(context) }

    var profileText by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val savedProfile = userProfileRepo.userProfileFlow.firstOrNull()
        if (savedProfile != null) {
            profileText = savedProfile
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Sarcastic Accountability",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = profileText,
            onValueChange = { profileText = it },
            label = { Text("Your Goals and Fears") },
            placeholder = { Text("e.g. I am prepping for coding interviews and fear staying stagnant.") },
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            maxLines = 10
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                isSaving = true
                coroutineScope.launch {
                    userProfileRepo.saveUserProfile(profileText)
                    isSaving = false
                    Toast.makeText(context, "Profile Saved", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = !isSaving
        ) {
            Text("Save Profile")
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onRequestPermissions) {
            Text("Grant Permissions & Start")
        }
    }
}

@Composable
fun AccountabilityTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

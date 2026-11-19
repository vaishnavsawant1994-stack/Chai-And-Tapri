package com.chaiandtapri.counter

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.text.InputType
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var model:CounterModel
    private lateinit var audio:AudioManager
    private lateinit var status:TextView
    private lateinit var headset:TextView
    private lateinit var microphone:TextView
    private lateinit var orders:TextView
    private lateinit var transcript:EditText
    private lateinit var language:Spinner
    private lateinit var speechInput:AndroidSpeechInput
    private var session="Stopped"
    private var routeId:Int?=null
    private var audioCallbackRegistered=false
    private var pending:ProposedCommand?=null
    private var pendingId=""
    private lateinit var maintenanceBanner:TextView
    private val actionButtons=mutableListOf<Button>()
    private var recoveryBusy=false
    private var recoveryOnlyMode=false
    private var hasResumedOnce=false
    private var workspaceInitializing=false
    private var exportSecret:CharArray?=null
    private var restoreSecret:CharArray?=null
    private val recoveryExecutor=RecoveryExecutor.executor
    private val barrierListener:(String?)->Unit={ label ->
        runOnUiThread {
            if(::maintenanceBanner.isInitialized){
                maintenanceBanner.text=label?.let{"MAINTENANCE · $it · counter changes are paused"}?:if(recoveryBusy)"Recovery operation starting…" else "Ready · local encrypted storage"
                maintenanceBanner.setBackgroundColor(if(label==null)0xffe8f5e9.toInt() else 0xffffe0b2.toInt())
            }
            if(label==null&&::audio.isInitialized&&!::model.isInitialized&&!recoveryOnlyMode)initializeWorkspace()
        }
    }

    private val createBackupLauncher=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){ uri ->
        val secret=exportSecret; exportSecret=null
        if(uri==null){secret?.fill('\u0000');setRecoveryBusy(false);status.text="Backup export cancelled; shop data was not changed."}
        else if(secret!=null)writeBackup(uri,secret)
        else{setRecoveryBusy(false);status.text="Backup secret expired; start export again."}
    }
    private val openBackupLauncher=registerForActivityResult(ActivityResultContracts.OpenDocument()){ uri ->
        if(uri==null){setRecoveryBusy(false);status.text="Restore selection cancelled; shop data was not changed."}
        else promptRecoverySecret("Verify encrypted backup"){ secret -> restoreSecret=secret;verifyBackup(uri,secret) }
    }

    private val permissionRequest=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){ grants->
        val micOk=ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
        if(!micOk){session="PermissionDenied · typed mode remains available";renderStatus();return@registerForActivityResult}
        if(Build.VERSION.SDK_INT>=31 && grants[Manifest.permission.BLUETOOTH_CONNECT]==false)headset.text="Bluetooth permission denied · headset route cannot be inspected"
        startOneUtterance()
    }
    private val bluetoothPermissionRequest=registerForActivityResult(ActivityResultContracts.RequestPermission()){
        refreshDevices()
        status.text=if(it)"Bluetooth permission granted; select the headset route explicitly."else"Bluetooth permission denied; typed/phone-mic use remains available."
    }
    private val deviceCallback=object:AudioDeviceCallback(){
        override fun onAudioDevicesAdded(addedDevices:Array<out AudioDeviceInfo>)=refreshDevices()
        override fun onAudioDevicesRemoved(removedDevices:Array<out AudioDeviceInfo>){
            if(removedDevices.any{it.id==routeId}){
                if(::speechInput.isInitialized)speechInput.stop()
                audio.clearCommunicationDevice();audio.mode=AudioManager.MODE_NORMAL;routeId=null
                session="Disconnected · listening stopped; explicitly resume after reconnect"
            }
            refreshDevices();renderStatus()
        }
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val recoveryEntry = intent.getBooleanExtra(EXTRA_OPEN_RECOVERY, false)
        if (!recoveryEntry && !LocalSessionState.authenticated) {
            startActivity(android.content.Intent(this, LocalAccessActivity::class.java))
            finish()
            return
        }
        audio=getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if(recoveryEntry){
            showRecoveryWaiting("Recovery is available without opening the local shop database.")
            showRecoveryMenu()
            return
        }
        initializeWorkspace()
    }

    override fun onResume(){
        super.onResume()
        if(hasResumedOnce&&::model.isInitialized&&!workspaceInitializing&&BusinessWriteBarrier.currentMaintenance()==null)refreshWorkspaceOffMainThread()
        hasResumedOnce=true
    }

    private fun initializeWorkspace(){
        if(::model.isInitialized||workspaceInitializing||recoveryOnlyMode)return
        workspaceInitializing=true
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(32,64,32,32)}
        column.addView(label("Opening encrypted shop records…",20f))
        column.addView(button("Backup & Recovery"){recoveryOnlyMode=true;showRecoveryMenu()})
        setContentView(ScrollView(this).apply{addView(column)})
        recoveryExecutor.execute{
            val opened=runCatching{CounterModel(SqliteCounterPersistence(this),requireConfiguredProducts=true)}
            runOnUiThread{
                workspaceInitializing=false
                if(isFinishing||isDestroyed){opened.getOrNull()?.close();return@runOnUiThread}
                opened.fold({fresh->
                    model=fresh
                    if(!audioCallbackRegistered){audio.registerAudioDeviceCallback(deviceCallback,null);audioCallbackRegistered=true}
                    buildScreen()
                    speechInput=AndroidSpeechInput(this,::onSpeechEvent)
                    refreshDevices();renderOrders()
                },{error->
                    showRecoveryWaiting(if(BusinessWriteBarrier.currentMaintenance()!=null)"Recovery is finishing. The shop database is locked safely; this screen will reopen when it is ready." else "The local shop database needs recovery. No new empty shop was created (${error.javaClass.simpleName}).")
                })
            }
        }
    }

    private fun refreshWorkspaceOffMainThread(){
        if(workspaceInitializing||recoveryOnlyMode)return
        workspaceInitializing=true
        recoveryExecutor.execute{
            val refreshed=runCatching{CounterModel(SqliteCounterPersistence(this),requireConfiguredProducts=true)}
            runOnUiThread{
                workspaceInitializing=false
                if(isFinishing||isDestroyed){refreshed.getOrNull()?.close();return@runOnUiThread}
                refreshed.fold({fresh->if(::model.isInitialized)model.close();model=fresh;if(::orders.isInitialized)renderOrders()},
                    {error->if(::status.isInitialized)status.text="Local data refresh failed; no new action was taken (${error.javaClass.simpleName})."})
            }
        }
    }

    private fun showRecoveryWaiting(message:String){
        recoveryOnlyMode=true
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(32,64,32,32)}
        column.addView(label("Shop data is protected",22f))
        column.addView(label(message,16f))
        status=label("Recovery mode · no business changes are available until local data is restored.",14f);column.addView(status)
        column.addView(button("Backup & Recovery"){showRecoveryMenu()})
        column.addView(button("Retry local store"){initializeWorkspace()})
        setContentView(ScrollView(this).apply{addView(column)})
    }

    private fun buildScreen(){
        recoveryOnlyMode=false
        actionButtons.clear()
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}
        setContentView(ScrollView(this).apply{addView(column)})
        column.addView(label("Chai And Tapri · Android Voice Counter",22f))
        column.addView(label("One utterance per listen · review each command before applying",14f))
        column.addView(label("Local SQLite storage · sync not configured · records stay on this device",13f))
        column.addView(label("${if (LocalSessionState.isOwner) "Owner" else LocalSessionState.role?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Staff"}: ${LocalSessionState.ownerDisplayName ?: "local session"}",14f))
        column.addView(button("Lock session") {
            LocalSessionState.clear()
            startActivity(android.content.Intent(this, LocalAccessActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        })
        maintenanceBanner=label("Ready · local encrypted storage",14f).apply{setPadding(12,12,12,12);setBackgroundColor(0xffe8f5e9.toInt())}
        column.addView(maintenanceBanner)
        if (LocalSessionState.isOwner) {
            column.addView(button("Staff Accounts · local roles"){startActivity(android.content.Intent(this,StaffManagementActivity::class.java))})
            column.addView(button("Shop Setup · CT-02"){startActivity(android.content.Intent(this,ShopSetupActivity::class.java))})
            column.addView(button("Shop Home · CT-05"){startActivity(android.content.Intent(this,ShopHomeActivity::class.java))})
            column.addView(button("Device Permissions · CT-04"){startActivity(android.content.Intent(this,DevicePermissionsActivity::class.java))})
        }
        if (LocalSessionState.isOwner) {
            column.addView(button("Sales History · CT-11"){startActivity(android.content.Intent(this,SalesHistoryActivity::class.java))})
            column.addView(button("Refunds & Corrections · CT-12"){startActivity(android.content.Intent(this,RefundCorrectionActivity::class.java))})
            column.addView(button("Products & Prices · CT-13/14"){startActivity(android.content.Intent(this,ProductCatalogueActivity::class.java))})
            column.addView(button("Inventory Overview - CT-15"){startActivity(android.content.Intent(this,InventoryOverviewActivity::class.java))})
            column.addView(button("Customers & Udhar - CT-21/23"){startActivity(android.content.Intent(this,CustomerCreditActivity::class.java))})
            column.addView(button("Suppliers & Purchases - CT-17/20"){startActivity(android.content.Intent(this,ProcurementActivity::class.java))})
            column.addView(button("Expenses - CT-25"){startActivity(android.content.Intent(this,ExpensesActivity::class.java))})
            column.addView(button("Cash Register - CT-26"){startActivity(android.content.Intent(this,CashRegisterActivity::class.java))})
            column.addView(button("Staff Shifts - CT-27"){startActivity(android.content.Intent(this,StaffShiftsActivity::class.java))})
            column.addView(button("Daily Closing Review - CT-28"){startActivity(android.content.Intent(this,DailyClosingActivity::class.java))})
            column.addView(button("Daily Reports - CT-29"){startActivity(android.content.Intent(this,DailyReportActivity::class.java))})
            column.addView(button("Business Alerts - CT-30"){startActivity(android.content.Intent(this,BusinessAlertsActivity::class.java))})
            column.addView(button("Voice Settings - CT-31"){startActivity(android.content.Intent(this,VoiceSettingsActivity::class.java))})
        }
        if (LocalSessionState.role == StaffRole.MANAGER) {
            column.addView(button("Shop Home · CT-05"){startActivity(android.content.Intent(this,ShopHomeActivity::class.java))})
            column.addView(button("Sales History · CT-11"){startActivity(android.content.Intent(this,SalesHistoryActivity::class.java))})
            column.addView(button("Refunds & Corrections · CT-12"){startActivity(android.content.Intent(this,RefundCorrectionActivity::class.java))})
            column.addView(button("Inventory Overview · CT-15"){startActivity(android.content.Intent(this,InventoryOverviewActivity::class.java))})
            column.addView(button("Customers & Udhar · CT-21/23"){startActivity(android.content.Intent(this,CustomerCreditActivity::class.java))})
            column.addView(button("Suppliers & Purchases · CT-17/20"){startActivity(android.content.Intent(this,ProcurementActivity::class.java))})
            column.addView(button("Expenses · CT-25"){startActivity(android.content.Intent(this,ExpensesActivity::class.java))})
            column.addView(button("Cash Register · CT-26"){startActivity(android.content.Intent(this,CashRegisterActivity::class.java))})
            column.addView(button("Daily Closing · CT-28"){startActivity(android.content.Intent(this,DailyClosingActivity::class.java))})
            column.addView(button("Daily Reports · CT-29"){startActivity(android.content.Intent(this,DailyReportActivity::class.java))})
        }
        column.addView(button("Quick Sale · CT-06"){startActivity(android.content.Intent(this,QuickSaleActivity::class.java))})
        column.addView(button("Live Orders · CT-08/10"){startActivity(android.content.Intent(this,LiveOrdersActivity::class.java))})
        column.addView(button("Payments · CT-10"){startActivity(android.content.Intent(this,PaymentActivity::class.java))})
        if (LocalSessionState.isOwner) column.addView(button("Backup & Recovery"){showRecoveryMenu()})
        status=label("Session: $session",16f);column.addView(status)
        microphone=label("Microphone: permission status checking",14f);column.addView(microphone)
        headset=label("Headset: checking…",14f);column.addView(headset)
        column.addView(label("Android SpeechRecognizer handles one utterance at a time; provider/network audio handling varies by device. This app does not save audio.",12f))
        column.addView(label("Recognition language",14f))
        language=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Hindi / Hinglish (hi-IN)","Marathi (mr-IN)","English India (en-IN)"))}
        language.setSelection(when(model.voiceLanguageTag()){ "mr-IN"->1;"en-IN"->2;else->0 })
        column.addView(language)
        column.addView(button("Select connected headset route"){selectHeadsetRoute()})
        column.addView(button("Start Listening"){startSession()})
        column.addView(button("Pause"){pauseSession()})
        column.addView(button("Resume"){resumeSession()})
        column.addView(button("Stop"){stopSession("Stopped")})
        column.addView(button("Listen for next command"){startListeningAgain()})
        column.addView(label("Recognized transcript · editable",16f))
        transcript=EditText(this).apply{hint="Naya order, do cutting chai";minLines=2}
        column.addView(transcript)
        column.addView(button("Propose command"){propose()})
        column.addView(button("Apply reviewed command"){applyPending()})
        column.addView(button("Reject proposed command"){rejectPending()})
        column.addView(button("Correction: cancel newest open order"){confirmCancelNewest()})
        column.addView(label("Demo transcript controls",16f))
        column.addView(button("Demo: new order · do cutting chai"){useSample("Naya order, do cutting chai")})
        column.addView(button("Demo: order 12 ready"){useSample("Order 12 ready hai")})
        column.addView(button("Demo: order 12 handed over"){useSample("Order 12 de diya")})
        column.addView(button("Demo: order 12 cash received"){useSample("Order 12 cash payment mila")})
        orders=label("No orders",14f);column.addView(orders)
    }

    private fun useSample(text:String){transcript.setText(text);propose()}
    private fun startSession(){
        if(session=="Stopped"||session.startsWith("Disconnected")||session.startsWith("PermissionDenied"))requestPermissionsIfNeeded()
        else status.text="Start is available only from Stopped or Disconnected."
    }
    private fun pauseSession(){
        if(session=="Listening"||session=="Awaiting confirmation"){speechInput.stop();session="Paused";renderStatus()}
        else status.text="Pause is available only during an active session."
    }
    private fun resumeSession(){if(session=="Paused"||session.startsWith("Paused ·"))requestPermissionsIfNeeded()else status.text="Resume is available only from Paused."}
    private fun requestPermissionsIfNeeded(){
        val missing=buildList{
            if(ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)add(Manifest.permission.RECORD_AUDIO)
            if(Build.VERSION.SDK_INT>=31&&ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if(missing.isEmpty())startOneUtterance()else permissionRequest.launch(missing.toTypedArray())
    }
    private fun startOneUtterance(){
        if(!SpeechRecognizer.isRecognitionAvailable(this)){session="Recognition unavailable · type commands instead";renderStatus();return}
        session=if(routeId==null)"Listening · Android default microphone route (source not verified)" else "Listening · headset route requested (source not verified)";renderStatus()
        val locale=when(language.selectedItemPosition){1->"mr-IN";2->"en-IN";else->"hi-IN"}
        speechInput.start(locale)
    }
    private fun startListeningAgain(){
        if(session=="Stopped"||session=="Paused"||session.startsWith("Paused ·"))status.text="Start or resume the listening session first."
        else requestPermissionsIfNeeded()
    }
    private fun onSpeechEvent(event:SpeechInputEvent){
        when(event){
            SpeechInputEvent.Ready->{session="Listening";renderStatus()}
            SpeechInputEvent.SpeechStarted->status.text="Speech detected · waiting for final transcript"
            is SpeechInputEvent.Transcript->{transcript.setText(event.text);propose()}
            is SpeechInputEvent.Failure->{session="Recognition error · ${event.label} · typed fallback available";renderStatus()}
        }
    }
    private fun propose(){
        pending=model.parse(transcript.text.toString());pendingId="tap-${System.nanoTime()}"
        session=if(pending!=null)"Awaiting confirmation"else"Command not understood · typed correction available"
        status.text=pending?.let{"Review: ${it.description}"}?:"Unsupported or ambiguous command · no change made"
    }
    private fun applyPending(){
        val command=pending?:run{status.text="No proposed command to apply.";return}
        status.text=model.apply(pendingId,command);pending=null
        session="Ready · tap Listen for next command";renderOrders()
    }
    private fun rejectPending(){
        if(pending==null){status.text="No proposed command to reject.";return}
        pending=null;pendingId="";session="Ready · tap Listen for next command"
        status.text="Proposed command rejected; no business data changed."
    }
    private fun confirmCancelNewest(){
        val order=model.allOrders().firstOrNull{it.fulfillment!=Fulfillment.GIVEN&&it.fulfillment!=Fulfillment.CANCELLED}
        if(order==null){status.text="No cancellable open order.";return}
        val reason=EditText(this).apply{hint="Reason for correction"}
        AlertDialog.Builder(this).setTitle("Cancel order #${order.number}?")
            .setMessage("Cancellation remains visible in the local demo audit history.").setView(reason)
            .setNegativeButton("Keep order",null).setPositiveButton("Record cancellation"){_,_->
                val text=reason.text.toString().trim()
                if(text.isEmpty())status.text="Enter a reason; nothing changed."
                else{status.text=model.cancelOrder(order.number,text);renderOrders()}
            }.show()
    }
    private fun refreshDevices(){
        microphone.text=if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)"Microphone: permission granted · capture only during a user-started utterance"else"Microphone: permission not granted"
        if(Build.VERSION.SDK_INT>=31&&ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){headset.text="Headset: grant Bluetooth permission to inspect communication routes";return}
        val outputs=if(Build.VERSION.SDK_INT>=31)audio.availableCommunicationDevices else emptyList()
        val device=outputs.firstOrNull{it.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||it.type==AudioDeviceInfo.TYPE_BLE_HEADSET}
        headset.text=if(device==null)"Headset: no Bluetooth communication output detected"else"Headset: ${device.productName} · route ${if(device.id==routeId)"selected"else"available"}"
    }
    private fun selectHeadsetRoute(){
        if(Build.VERSION.SDK_INT<31){status.text="Communication-device routing requires Android 12+.";return}
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){bluetoothPermissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT);return}
        val device=audio.availableCommunicationDevices.firstOrNull{it.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||it.type==AudioDeviceInfo.TYPE_BLE_HEADSET}
        if(device==null){status.text="No Bluetooth communication route detected.";return}
        audio.mode=AudioManager.MODE_IN_COMMUNICATION
        val ok=audio.setCommunicationDevice(device);routeId=if(ok)device.id else null
        val inputs=audio.getDevices(AudioManager.GET_DEVICES_INPUTS).filter{it.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||it.type==AudioDeviceInfo.TYPE_BLE_HEADSET}
        headset.text=if(ok)"Route accepted · input candidates: ${inputs.joinToString{it.productName}} · speech service source not independently verified"else"Route rejected · use visible typed or phone-mic fallback"
        status.text=if(ok)"Android communication route selected; microphone source remains unverified."else"Route selection failed."
    }
    private fun stopSession(message:String){speechInput.stop();audio.clearCommunicationDevice();audio.mode=AudioManager.MODE_NORMAL;routeId=null;session=message;renderStatus()}
    private fun renderStatus(){status.text="Session: $session";refreshDevices()}
    private fun renderOrders(){orders.text=model.allOrders().joinToString("\n"){"#${it.number}: ${it.items} · ${money(it.totalPaise)} due ${money(it.duePaise)} · ${it.fulfillment} / ${it.payment}"}.ifBlank{"No orders"}}
    private fun money(paise:Int)="₹${paise/100}.${(paise%100).toString().padStart(2,'0')}"
    private fun label(text:String,size:Float)=TextView(this).apply{this.text=text;textSize=size;setPadding(0,8,0,8)}
    private fun button(text:String,action:()->Unit)=Button(this).apply{this.text=text;setOnClickListener{if(!recoveryBusy)action()};layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);actionButtons+=this}

    private fun showRecoveryMenu(){
        val ownerAuthenticated = LocalSessionState.authenticated
        val builder = AlertDialog.Builder(this).setTitle("Backup & Recovery")
            .setMessage("Encrypted backups are bound to a generated shop identity and protected by a separate recovery secret. An existing shop can only be replaced by a backup with the same identity. A new empty installation adopts the selected backup identity. Keep the recovery secret separately; restoring replaces local records only after verification and confirmation.")
        if (ownerAuthenticated) {
            builder.setPositiveButton("Create encrypted backup"){_,_->promptRecoverySecret("Set recovery secret",confirm=true){secret->
                exportSecret=secret;setRecoveryBusy(true);createBackupLauncher.launch("chai-and-tapri-backup.ctbackup")
            }}
                .setNegativeButton("Restore from backup"){_,_->setRecoveryBusy(true);openBackupLauncher.launch(arrayOf("application/octet-stream","application/*","*/*"))}
        } else {
            builder.setPositiveButton("Restore from backup"){_,_->setRecoveryBusy(true);openBackupLauncher.launch(arrayOf("application/octet-stream","application/*","*/*"))}
        }
        builder.setNeutralButton("Cancel",null).show()
    }

    private fun promptRecoverySecret(title:String,confirm:Boolean=false,onSecret:(CharArray)->Unit):AlertDialog{
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(28,8,28,0)}
        val first=EditText(this).apply{hint="Recovery secret (12+ characters)";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO}
        box.addView(first)
        val second=if(confirm)EditText(this).apply{hint="Confirm recovery secret";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO}else null
        second?.let(box::addView)
        var accepted=false
        fun releaseCancelledPrompt(){
            first.text.clear();second?.text?.clear();setRecoveryBusy(false)
            if(::status.isInitialized)status.text="Recovery secret entry cancelled; local shop data was not changed."
        }
        val dialog=AlertDialog.Builder(this).setTitle(title).setView(box).setNegativeButton("Cancel"){_,_->
            releaseCancelledPrompt()
        }
            .setPositiveButton("Continue",null).create()
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener{releaseCancelledPrompt()}
        dialog.setOnDismissListener{if(!accepted)releaseCancelledPrompt()}
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val firstChars=CharArray(first.text.length){index->first.text[index]}
            if(firstChars.size<12){firstChars.fill('\u0000');first.error="Use at least 12 characters";return@setOnClickListener}
            val secondChars=second?.let{field->CharArray(field.text.length){index->field.text[index]}}
            if(confirm&&(secondChars==null||!firstChars.contentEquals(secondChars))){firstChars.fill('\u0000');secondChars?.fill('\u0000');second?.error="The secrets do not match";return@setOnClickListener}
            secondChars?.fill('\u0000');first.text.clear();second?.text?.clear();accepted=true;dialog.dismiss();onSecret(firstChars)
        }}
        dialog.show()
        return dialog
    }

    private fun writeBackup(uri:Uri,secret:CharArray){
        setRecoveryBusy(true);status.text="Preparing encrypted backup…"
        recoveryExecutor.execute{
            val result=runCatching{BackupExportPolicy.write(
                writeDocument={
                    contentResolver.openOutputStream(uri,"w")?.use{PortableSnapshotBackup(this).export(it,secret.copyOf())}
                        ?:error("The selected destination could not be opened.")
                },
                deletePartialDocument={android.provider.DocumentsContract.deleteDocument(contentResolver,uri)}
            )}
            secret.fill('\u0000')
            runOnUiThread{setRecoveryBusy(false);status.text=result.fold(
                {"Encrypted backup created in the selected location."},
                {error->when(error){
                    is BackupExportException->if(error.partialDocumentRemoved)
                        "Backup failed and the incomplete destination file was removed. Local shop data was preserved."
                    else "Backup failed; an incomplete file may remain in the selected location. Remove it before trying again. Local shop data was preserved."
                    else->"Backup failed; local shop data was preserved (${error.javaClass.simpleName})."
                }}
            )}
        }
    }

    private fun verifyBackup(uri:Uri,secret:CharArray){
        setRecoveryBusy(true);status.text="Verifying encrypted backup…"
        recoveryExecutor.execute{
            val result=runCatching{
                val stream=contentResolver.openInputStream(uri)?:error("Selected backup could not be opened.")
                stream.use { PortableSnapshotBackup(this).verify(it,secret.copyOf()) }
            }
            runOnUiThread{
                setRecoveryBusy(false)
            result.fold({metadata->
                    status.text="Backup verified. Review the metadata before choosing whether to replace local data."
                    AlertDialog.Builder(this).setTitle("Backup verified")
                        .setMessage("Encrypted and integrity-checked snapshot. Schema v${metadata.schemaVersion}; format v${metadata.formatVersion}; snapshot ID ending ${metadata.snapshotIdSuffix}; shop ID ending ${metadata.shopIdentitySuffix}; ${metadata.encryptedPayloadBytes} encrypted payload bytes. The authenticated shop identity is checked against existing data when that data can be opened. If the device key is unavailable, verify that this backup belongs to the intended shop before continuing; inaccessible prior ciphertext is retained as an encrypted archive after the restored database passes verification.")
                        .setNegativeButton("Cancel"){_,_->discardRestoreSecret(secret);status.text="Restore cancelled; active data was not changed."}
                        .setPositiveButton("Continue"){_,_->confirmRestore(uri,secret)}.show()
                },{error->
                    discardRestoreSecret(secret)
                    val safeReason=if(error is IllegalArgumentException) error.message?.takeIf{it.isNotBlank()}?.take(180) else null
                    status.text="Backup verification failed; active data was not changed${safeReason?.let{": $it"}?:" (${error.javaClass.simpleName})"}."
                })
            }
        }
    }

    private fun confirmRestore(uri:Uri,secret:CharArray){
        AlertDialog.Builder(this).setTitle("Replace local shop data?")
            .setMessage("This replaces all local orders, payments, credit, stock and pending records with the selected snapshot. Make sure it is the intended shop backup. The current database is preserved until restored data is verified. If the device key is unavailable, the old encrypted database cannot be checked against the backup; its ciphertext will be retained in an archive after verification.")
            .setNegativeButton("Keep current data"){_,_->discardRestoreSecret(secret);status.text="Restore cancelled; active data was not changed."}
            .setPositiveButton("Replace and restore"){_,_->runRestore(uri,secret)}.show()
    }

    private fun runRestore(uri:Uri,secret:CharArray){
        setRecoveryBusy(true);status.text="Closing local store for verified restore…"
        if(restoreSecret===secret)restoreSecret=null
        LocalSessionState.clear()
        if(::model.isInitialized)model.close()
        recoveryExecutor.execute{
            val result=runCatching{
                val stream=contentResolver.openInputStream(uri)?:error("Selected backup could not be opened.")
                stream.use { PortableSnapshotBackup(this).restore(it,secret.copyOf(),true) }
            }
            secret.fill('\u0000')
            val reopened=runCatching{
                if(::model.isInitialized)model.close()
                CounterModel(SqliteCounterPersistence(this),requireConfiguredProducts=true)
            }
            runOnUiThread{
                setRecoveryBusy(false)
                if(isFinishing||isDestroyed){reopened.getOrNull()?.close();return@runOnUiThread}
                if(reopened.isFailure){
                    if(!::status.isInitialized)showRecoveryWaiting("Restore finished, but the local database could not be reopened. Preserve all files and retry recovery.")
                    else status.text="Restore finished, but local database reopening failed. Preserve all files and contact support."
                } else {
                    model=reopened.getOrThrow()
                    if(!LocalSessionState.authenticated){
                        startActivity(android.content.Intent(this, LocalAccessActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                        return@runOnUiThread
                    }
                    if(recoveryOnlyMode)buildScreen()
                    renderOrders()
                    status.text=result.fold({"Backup restored and encrypted database reopened successfully."},{"Restore failed safely; previous data was preserved (${it.javaClass.simpleName})."})
                }
            }
        }
    }

    private fun setRecoveryBusy(busy:Boolean){
        recoveryBusy=busy
        actionButtons.forEach{it.isEnabled=!busy}
        if(::maintenanceBanner.isInitialized){
            val maintenance=BusinessWriteBarrier.currentMaintenance()
            maintenanceBanner.text=maintenance?.let{"MAINTENANCE · $it · counter changes are paused"}
                ?:if(busy)"Recovery operation starting…" else "Ready · local encrypted storage"
            maintenanceBanner.setBackgroundColor(if(maintenance==null)0xffe8f5e9.toInt() else 0xffffe0b2.toInt())
        }
    }

    private fun discardRestoreSecret(secret:CharArray){secret.fill('\u0000');if(restoreSecret===secret)restoreSecret=null}

    override fun onStart(){super.onStart();BusinessWriteBarrier.addListener(barrierListener)}
    override fun onStop(){BusinessWriteBarrier.removeListener(barrierListener);if(::speechInput.isInitialized&&session.contains("Listening")){speechInput.stop();audio.clearCommunicationDevice();audio.mode=AudioManager.MODE_NORMAL;routeId=null;session="Paused · foreground listening stopped; route released"};super.onStop()}
    override fun onDestroy(){exportSecret?.fill('\u0000');exportSecret=null;restoreSecret?.fill('\u0000');restoreSecret=null;if(::speechInput.isInitialized)speechInput.destroy();if(audioCallbackRegistered&&::audio.isInitialized)audio.unregisterAudioDeviceCallback(deviceCallback);if(::audio.isInitialized){audio.clearCommunicationDevice();audio.mode=AudioManager.MODE_NORMAL};if(::model.isInitialized)model.close();super.onDestroy()}

    companion object { const val EXTRA_OPEN_RECOVERY = "com.chaiandtapri.counter.OPEN_RECOVERY" }
}

internal object RecoveryExecutor {
    val executor=java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable,"tapri-backup-recovery").apply { isDaemon=true }
    }
}



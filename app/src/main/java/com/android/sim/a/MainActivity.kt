package com.android.sim.a

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.android.sim.a.databinding.ActivityMainBinding
import com.android.sim.a.root.RootShell
import com.android.sim.a.sim.SimHardwareController
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var detectedMsisdnRecordLen = 28
    private var detectedFplmnByteLen = 12

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        initRootAndPorts()
    }

    private fun setupListeners() {
        binding.btnRefreshStatus.setOnClickListener {
            refreshSimStatus()
        }

        binding.btnReadMsisdn.setOnClickListener {
            val port = getSelectedPort() ?: return@setOnClickListener
            appendLog("جاري قراءة EF_MSISDN (6F40)...")
            executor.execute {
                val (record, raw) = SimHardwareController.readMsisdn(port)
                mainHandler.post {
                    if (record != null) {
                        detectedMsisdnRecordLen = record.recordLength
                        binding.etAlphaTag.setText(record.alphaTag)
                        binding.etPhoneNumber.setText(record.phoneNumber)
                        appendLog("تمت قراءة MSISDN (طول السجل: ${record.recordLength} بايت):\nالاسم: ${record.alphaTag} | الرقم: ${record.phoneNumber}")
                    } else {
                        appendLog("فشلت قراءة MSISDN:\n$raw")
                    }
                }
            }
        }

        binding.btnWriteMsisdn.setOnClickListener {
            val port = getSelectedPort() ?: return@setOnClickListener
            val tag = binding.etAlphaTag.text.toString().trim()
            val number = binding.etPhoneNumber.text.toString().trim()
            appendLog("جاري كتابة EF_MSISDN إلى الشريحة...")
            executor.execute {
                val (ok, log) = SimHardwareController.writeMsisdn(port, tag, number, detectedMsisdnRecordLen)
                mainHandler.post {
                    appendLog(if (ok) "تم التعديل الفعلي على الشريحة بنجاح!\n$log" else "فشل التعديل (قد يكون الحقل مقفلاً من المشغل):\n$log")
                    Toast.makeText(
                        this,
                        if (ok) "تم حفظ الرقم على الشريحة بنجاح" else "فشل الحفظ - راجع السجل",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        binding.btnReadFplmn.setOnClickListener {
            val port = getSelectedPort() ?: return@setOnClickListener
            appendLog("جاري قراءة EF_FPLMN (6F7B)...")
            executor.execute {
                val (list, byteLen, raw) = SimHardwareController.readFplmn(port)
                mainHandler.post {
                    detectedFplmnByteLen = byteLen
                    val text = if (list.isEmpty()) "لا توجد شبكات محظورة" else list.joinToString(", ")
                    binding.etFplmnList.setText(if (list.isEmpty()) "" else list.joinToString(","))
                    appendLog("الشبكات المحظورة ($byteLen بايت): $text\n$raw")
                }
            }
        }

        binding.btnClearFplmn.setOnClickListener {
            val port = getSelectedPort() ?: return@setOnClickListener
            appendLog("جاري مسح جميع الشبكات المحظورة (FF)...")
            executor.execute {
                val (ok, log) = SimHardwareController.writeFplmn(port, emptyList(), detectedFplmnByteLen)
                mainHandler.post {
                    if (ok) binding.etFplmnList.setText("")
                    appendLog(if (ok) "تم مسح EF_FPLMN بنجاح!\n$log" else "فشل مسح EF_FPLMN:\n$log")
                }
            }
        }

        binding.btnWriteFplmn.setOnClickListener {
            val port = getSelectedPort() ?: return@setOnClickListener
            val rawInput = binding.etFplmnList.text.toString().trim()
            val plmns = rawInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            appendLog("جاري كتابة EF_FPLMN...")
            executor.execute {
                val (ok, log) = SimHardwareController.writeFplmn(port, plmns, detectedFplmnByteLen)
                mainHandler.post {
                    appendLog(if (ok) "تم تحديث EF_FPLMN بنجاح!\n$log" else "فشل التحديث:\n$log")
                }
            }
        }

        binding.btnSendAt.setOnClickListener {
            val port = getSelectedPort() ?: return@setOnClickListener
            val cmd = binding.etAtCommand.text.toString().trim()
            if (cmd.isEmpty()) return@setOnClickListener
            appendLog(">> $cmd")
            executor.execute {
                val res = RootShell.sendAtCommand(port, cmd)
                mainHandler.post {
                    appendLog("<< $res")
                }
            }
        }

        binding.btnRestartRadio.setOnClickListener {
            val port = getSelectedPort() ?: return@setOnClickListener
            appendLog("جاري إعادة تشغيل الراديو (CFUN=0/1)...")
            executor.execute {
                val res = SimHardwareController.refreshRadio(port)
                mainHandler.post {
                    appendLog(res)
                }
            }
        }
    }

    private fun initRootAndPorts() {
        executor.execute {
            val hasRoot = RootShell.isRootAvailable()
            val ports = if (hasRoot) RootShell.detectAtPorts() else emptyList()
            mainHandler.post {
                binding.tvRootStatus.text = if (hasRoot) "حالة الروت: متاح (UID=0)" else "حالة الروت: غير متاح!"
                if (ports.isNotEmpty()) {
                    val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ports)
                    binding.spinnerPorts.adapter = adapter
                    binding.etCustomPort.setText(ports.first())
                    appendLog("تم اكتشاف منافذ المودم: ${ports.joinToString(", ")}")
                } else {
                    appendLog("لم يتم اكتشاف منفذ AT تلقائياً. يمكنك كتابة المسار يدوياً (مثل /dev/smd0).")
                }
                if (hasRoot) {
                    refreshSimStatus()
                }
            }
        }
    }

    private fun refreshSimStatus() {
        val port = binding.etCustomPort.text.toString().trim().ifBlank {
            binding.spinnerPorts.selectedItem?.toString()
        }
        executor.execute {
            val info = SimHardwareController.readSystemSimStatus(port)
            mainHandler.post {
                binding.tvSimInfo.text = buildString {
                    append("حالة الشريحة: ").append(info.simState).append("\n")
                    append("المشغل: ").append(info.operatorAlpha).append(" (").append(info.operatorNumeric).append(")\n")
                    append("الدولة: ").append(info.countryIso).append(" | التجوال: ").append(info.isRoaming).append("\n")
                    append("ICCID: ").append(info.iccid).append("\n")
                    append("IMSI: ").append(info.imsi)
                }
            }
        }
    }

    private fun getSelectedPort(): String? {
        val custom = binding.etCustomPort.text.toString().trim()
        val port = custom.ifBlank { binding.spinnerPorts.selectedItem?.toString().orEmpty() }
        if (port.isBlank()) {
            Toast.makeText(this, "يرجى تحديد منفذ المودم أولاً", Toast.LENGTH_SHORT).show()
            return null
        }
        return port
    }

    private fun appendLog(msg: String) {
        val current = binding.tvLog.text.toString()
        binding.tvLog.text = "$msg\n--------------------\n$current"
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}

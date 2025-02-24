package org.avventomedia.app.telefyna.listen.mail

import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import org.avventomedia.app.telefyna.Monitor
import org.avventomedia.app.telefyna.Utils
import org.avventomedia.app.telefyna.audit.AuditAlert
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger
import org.avventomedia.app.telefyna.modal.Receivers
import java.io.File
import java.io.UnsupportedEncodingException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.DataSource
import javax.activation.FileDataSource
import javax.mail.BodyPart
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Session
import javax.mail.internet.AddressException
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

class Mail(private val auditAlert: AuditAlert) {
    private val emailProperties: Properties = System.getProperties().apply {
        put("mail.smtp.port", auditAlert.alerts.mailer?.port ?: "")
        put("mail.smtp.auth", "true")
        put("mail.smtp.starttls.enable", "true")
    }
    private lateinit var mailSession: Session
    private lateinit var emailMessage: MimeMessage

    @RequiresApi(Build.VERSION_CODES.O)
    private fun decode(str: String): String {
        return String(Base64.getDecoder().decode(str), StandardCharsets.UTF_8)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun decodePass(pass: String): String {
        val cleanedPass = pass.dropLast(1) // drop random final number
        val hash = Base64.getEncoder().encodeToString("VkdoaGJtdHpSbTl5VlhOcGJtZFVaV3hsWm5sdVlTd2dWMlVnYkdGMVkyaGxaQ0JVWld4bFpubHVZU0JwYmlBeU1ESXhJR0o1SUVkdlpDZHpJR2R5WVdObA==".toByteArray())
        return decode(decode(decode(decode(decode(cleanedPass.replace(hash, ""))))))
    }

    @Throws(MessagingException::class, UnsupportedEncodingException::class)
    private fun createEmailMessage(receivers: Receivers, draft: Draft) {
        if (Utils.isValidEmail(draft.from)) {
            mailSession = Session.getDefaultInstance(emailProperties, null)
            emailMessage = MimeMessage(mailSession).apply {
                setFrom(InternetAddress(draft.from, draft.from))

                // Process BCC recipients
                receivers.emails.split("#").forEach { emailAdd ->
                    val trimmedEmail = emailAdd.trim()
                    if (Utils.isValidEmail(trimmedEmail)) {
                        try {
                            draft.bcc.add(InternetAddress(trimmedEmail))
                        } catch (e: AddressException) {
                            e.message?.let { Logger.log(AuditLog.Event.ERROR, it) }
                        }
                    }
                }

                addRecipients(Message.RecipientType.BCC, draft.bcc.toTypedArray())
                subject = draft.subject
                setEmailBody(receivers, draft)
            }
        }
    }


    @Throws(MessagingException::class)
    private fun attach(attachment: String, draft: Draft): BodyPart {
        return MimeBodyPart().apply {
            val source: DataSource = FileDataSource(attachment)
            dataHandler = DataHandler(source)
            fileName = attachment
        }
    }

    @OptIn(UnstableApi::class)
    @Throws(MessagingException::class)
    fun setEmailBody(receivers: Receivers, draft: Draft) {
        if (!receivers.isAttachConfig && receivers.attachAuditLog == 0) {
            emailMessage.setContent(draft.body, "text/html") // for a html email
        } else if (AuditLog.Event.MAINTENANCE == auditAlert.event) {
            val multipart = MimeMultipart()
            val config = Monitor.instance?.getConfigFile()

            if (receivers.isAttachConfig && File(config as String).exists()) {
                multipart.addBodyPart(attach(config, draft))
            }

            Logger.getAuditsForNDays(receivers.attachAuditLog).forEach { audit ->
                if (File(audit).exists()) {
                    multipart.addBodyPart(attach(audit, draft))
                }
            }
            emailMessage.setContent(multipart)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun mailNow(receivers: Receivers, draft: Draft) {
        try {
            createEmailMessage(receivers, draft)
            val transport = mailSession.getTransport("smtp")
            transport.connect(auditAlert.alerts.mailer?.host, draft.from, decodePass(draft.pass))
            transport.sendMessage(emailMessage, emailMessage.allRecipients)
            transport.close()
            Logger.log(AuditLog.Event.EMAIL, draft.subject, receivers.emails, "SUCCEEDED")
        } catch (e: Exception) {
            Logger.log(AuditLog.Event.EMAIL, draft.subject, receivers.emails.replace("#", ", "), "FAILED with: ${e.message}")
        }
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    fun sendEmail() {
        if (auditAlert.alerts.mailer != null) {
            val draft = Draft().apply {
                from = auditAlert.alerts.mailer?.email.toString()
                pass = auditAlert.alerts.mailer?.pass.toString()
                subject = Monitor.instance?.configuration?.let {
                    String.format("%s %s %s Alert: %s",
                        Logger.getToday(),
                        it.name,
                        auditAlert.event.getCategory(),
                        auditAlert.event.name)
                }.toString()
            }

            auditAlert.alerts.subscribers?.forEach { receivers ->
                when {
                    AuditLog.Event.Category.ADMIN == auditAlert.event.getCategory() -> {
                        draft.body = Monitor.instance?.configuration?.let {
                            String.format("Dear admin,<br><br> %s <br><br><br>This is a %s system notification, please don't respond to it.<br><br>TelefynaBot",
                                auditAlert.message,
                                it.name)
                        }.toString()
                        draft.allowsAttachments = true
                        mailNow(receivers, draft)
                    }

                    AuditLog.Event.Category.BROADCAST == receivers.eventCategory && AuditLog.Event.Category.BROADCAST == auditAlert.event.getCategory() -> {
                        draft.body = Monitor.instance?.configuration?.let {
                            String.format("Dear broadcaster,<br><br> %s <br><br><br>This is a %s system notification, please don't respond to it.<br><br>TelefynaBot",
                                auditAlert.message,
                                it.name)
                        }.toString()
                        mailNow(receivers, draft)
                    }
                }
            }
        }
    }
}

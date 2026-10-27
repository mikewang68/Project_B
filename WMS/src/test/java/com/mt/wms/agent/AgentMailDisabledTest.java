package com.mt.wms.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentMailDisabledTest {
    @Test void explicitDisabledMailDoesNotSendEvenWithSmtpConfiguration() {
        AgentRepository repository=mock(AgentRepository.class);
        @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> mail=mock(ObjectProvider.class);
        var service=new AgentPatrolService(repository,mock(AgentAnalytics.class),mock(TransactionTemplate.class),mail,
                "smtp.example.com","sender@example.com",true,false);
        assertFalse(service.mailReady());
        service.sendMail();
        verifyNoInteractions(repository,mail);
    }
}

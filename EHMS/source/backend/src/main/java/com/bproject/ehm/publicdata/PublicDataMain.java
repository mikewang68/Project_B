package com.bproject.ehm.publicdata;

import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Opt-in bounded CLI. No autostart, no daemon, no browser endpoint, no public generator mutation. */
public final class PublicDataMain {
    public static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public static CommittedInboxStore open(Path path)throws Exception {
        var c=PublicDataConfig.load(path,JSON);if(!c.enabled())throw new IllegalStateException("DISABLED_BY_DEFAULT_ENABLE_PRIVATE_CONFIG_FIRST");
        var source=new DriverManagerDataSource(c.jdbcUrl(),c.dbUser(),Files.readString(c.dbPasswordFile()).stripTrailing());
        return new CommittedInboxStore(new JdbcTemplate(source),c,new OwnerCatalog(c.catalogFile(),c.allocationFile(),JSON),JSON);
    }
    public static void main(String[] args){
        try{
            if(args.length<2)throw new IllegalArgumentException("usage: CONFIG init|status|process|retry|http [SECONDS MAX_NEW_EVENTS]");
            var store=open(Path.of(args[0]));String command=args[1];
            if(!command.equals("init"))store.verifyBinding();
            switch(command){
                case "init"->store.initialize();
                case "status"->{}
                case "process"->store.process(100);
                case "retry"->store.retryFailed();
                case "http"->{
                    if(args.length!=4)throw new IllegalArgumentException("EXPLICIT_BOUNDS_REQUIRED");
                    int seconds=Integer.parseInt(args[2]),max=Integer.parseInt(args[3]);
                    if(seconds<1||seconds>3600||max<1||max>10000)throw new IllegalArgumentException("INVALID_RUN_BOUNDS");
                    var reader=new CommittedHttpReader(store,CommittedHttpReader.network(store.config));var until=Instant.now().plusSeconds(seconds);int received=0;
                    while(Instant.now().isBefore(until)&&received<max){
                        int round=0;for(String device:new TreeSet<>(store.catalog.devices.keySet())){
                            if(received>=max||Instant.now().isAfter(until))break;
                            int count=reader.page(device,Math.min(20,max-received));received+=count;round+=count;store.process(100);
                        }
                        if(round==0)break; // bounded catch-up, no busy loop and no implicit generator start
                    }
                }
                default->throw new IllegalArgumentException("UNKNOWN_COMMAND");
            }
            System.out.println(JSON.writeValueAsString(store.status()));
        }catch(Exception failure){System.err.println("EHMS_PUBLIC_DATA_NOT_COMPLETED: "+failure.getClass().getSimpleName()+"; private operator review required; no credentials printed");System.exit(1);}
    }
}

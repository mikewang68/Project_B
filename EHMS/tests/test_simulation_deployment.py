import base64, hashlib, importlib.util, json, pathlib, tempfile, unittest

path=pathlib.Path(__file__).resolve().parents[1]/'scripts/deployment/install-running-server.py'
spec=importlib.util.spec_from_file_location('install_running',path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class DeploymentSafetyTest(unittest.TestCase):
    def frontend_manifest(self):
        assets={'index.html':b'<html>simulation-console.js</html>','simulation-console.js':b'//fixture-only'}
        manifest={'files':{'web/'+name:hashlib.sha256(body).hexdigest() for name,body in assets.items()}}
        return assets,manifest
    def test_frontend_reload_waits_for_exact_new_page_and_all_scripts(self):
        assets,manifest=self.frontend_manifest();now=[0];reports=[]
        def fetch(url):
            if now[0]==0:return 200,{},b'<html>old page</html>'
            if now[0]==1:return 403,{},b'<html>forbidden</html>'
            return 200,{},assets['index.html' if '/ehm/?' in url else 'simulation-console.js']
        result=module.verify_unified_frontend(manifest,reports.append,fetch=fetch,clock=lambda:now[0],sleep=lambda s:now.__setitem__(0,now[0]+s))
        self.assertEqual(result,{'verified':True,'verifiedFiles':2});self.assertEqual(now[0],2)
        self.assertNotIn('body',str(reports))
    def test_frontend_correct_index_with_missing_script_is_rejected(self):
        assets,manifest=self.frontend_manifest();now=[0];reports=[]
        def fetch(url):return (200,{},assets['index.html']) if '/ehm/?' in url else (403,{},b'forbidden')
        with self.assertRaisesRegex(RuntimeError,'UNIFIED_FRONTEND_NOT_VERIFIED'):
            module.verify_unified_frontend(manifest,reports.append,timeout=2,fetch=fetch,clock=lambda:now[0],sleep=lambda s:now.__setitem__(0,now[0]+s))
        self.assertEqual(reports[-1]['path'],'/ehm/simulation-console.js');self.assertEqual(reports[-1]['httpStatus'],403)
    def test_static_publish_copies_only_public_manifest_assets(self):
        with tempfile.TemporaryDirectory() as folder:
            root=pathlib.Path(folder);release=root/'data'/'fixture-release';base=root/'public';base.mkdir()
            (release/'web').mkdir(parents=True);assets,manifest=self.frontend_manifest()
            for name,body in assets.items():(release/'web'/name).write_bytes(body)
            (release/'private-deployment').mkdir();(release/'private-deployment'/'fixture.private').write_text('never-publish')
            manifest['files']['ehm-service.jar']='not-a-public-web-file'
            dest=module.prepare_static_frontend(release,manifest,base)
            self.assertEqual(dest,base/'fixture-release'/'ehms')
            self.assertEqual({p.name for p in dest.iterdir()},set(assets))
            self.assertEqual((dest/'index.html').read_bytes(),assets['index.html'])
            (dest/'index.html').write_bytes(b'conflicting-existing-artifact')
            with self.assertRaisesRegex(RuntimeError,'EXISTING_PUBLIC_WEB_ARTIFACT_DIFFERS'):
                module.prepare_static_frontend(release,manifest,base)
    def test_no_global_selinux_or_permission_relaxation(self):
        text=path.read_text(encoding='utf-8')
        for forbidden in ['setenforce','chmod 777','setsebool','semanage']:
            self.assertNotIn(forbidden,text)
        self.assertIn("command(restorecon,'-RF',str(destination.parent))",text)
        self.assertIn("'httpd_sys_content_t'",text)
    def sampling(self,**changes):
        return dict(initialized=True,running=True,assets=29,latestSamples=87,ticks=1,storedSamples=87,**changes)
    def test_sampling_waits_past_fifteen_second_scheduler_delay(self):
        now=[0];reports=[]
        def read():return self.sampling() if now[0]>=16 else {'initialized':True,'running':True,'assets':29,'ticks':0,'storedSamples':0}
        result=module.wait_for_sampling(read,{},reports.append,clock=lambda:now[0],sleep=lambda s:now.__setitem__(0,now[0]+s))
        self.assertEqual(now[0],16);self.assertEqual(result['storedSamples'],87);self.assertGreater(len(reports),7)
    def test_old_round_receipt_does_not_count_as_a_new_round(self):
        now=[0];state=self.sampling()
        with self.assertRaisesRegex(RuntimeError,'SIMULATED_SAMPLING_NOT_VERIFIED'):
            module.wait_for_sampling(lambda:state,state,timeout=20,clock=lambda:now[0],sleep=lambda s:now.__setitem__(0,now[0]+s))
    def test_receipt_without_committed_sample_growth_is_rejected(self):
        now=[0];state=self.sampling();state['storedSamples']=86
        with self.assertRaisesRegex(RuntimeError,'SIMULATED_SAMPLING_NOT_VERIFIED'):
            module.wait_for_sampling(lambda:state,{},timeout=2,clock=lambda:now[0],sleep=lambda s:now.__setitem__(0,now[0]+s))
    def test_failure_or_manual_pause_is_not_silently_restarted(self):
        for state,marker in [({'failure':'ROUND_ROLLED_BACK'},'SIMULATION_ROUND_FAILED'),
                             ({'initialized':True,'running':False},'SIMULATION_PAUSED')]:
            with self.assertRaisesRegex(RuntimeError,marker):module.wait_for_sampling(lambda:state,{})
    def test_env_reader_never_executes_shell_content(self):
        with tempfile.TemporaryDirectory() as folder:
            f=pathlib.Path(folder)/'test.env';f.write_text('EHM_AUTH_MODE=iam\nexport DUMMY="quoted value"\n# ignored\nRAW=literal-command\n')
            self.assertEqual(module.envfile(f),{'EHM_AUTH_MODE':'iam','DUMMY':'quoted value','RAW':'literal-command'})
    def test_env_multi_token_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            f=pathlib.Path(folder)/'test.env';f.write_text('BAD=value unexpected\n')
            with self.assertRaises(RuntimeError):module.envfile(f)
    def test_installer_retains_auth_and_only_scopes_ehm_override(self):
        text=path.read_text(encoding='utf-8')
        self.assertIn("settings.get('ehm.auth.mode')!='iam'",text)
        self.assertIn('ACCOUNT_ROWS_CHANGED',(path.parent/'SimulationDeploymentState.java').read_text(encoding='utf-8'))
        self.assertNotIn('DROP TABLE',text);self.assertNotIn('TRUNCATE',text)
        self.assertIn('PRIVATE_AUTH_CONFIG_CHANGED',text);self.assertIn('99-ehm-running-simulation.conf',text)
        self.assertIn('nginx.read_text()!=old_nginx',text)
    def test_real_process_json_and_jvm_properties_are_preserved_without_native_option_echo(self):
        env={'SPRING_APPLICATION_JSON':json.dumps({'ehm':{'auth':{'mode':'iam'}}}),
             'JAVA_TOOL_OPTIONS':'-Dehm.auth.mode=local','_JAVA_OPTIONS':'-Dfixture.sensitive=synthetic-only'}
        result=module.inspector_inputs(env,['java','-Dehm.auth.mode=iam','-jar','/fixture.jar','--server.port=12345'])
        self.assertEqual(result['SPRING_APPLICATION_JSON'],env['SPRING_APPLICATION_JSON'])
        self.assertNotIn('JAVA_TOOL_OPTIONS',result);self.assertNotIn('_JAVA_OPTIONS',result)
        self.assertEqual(json.loads(base64.b64decode(result['EHM_INSPECTOR_SYSTEM_PROPERTIES'])),{'ehm.auth.mode':'iam','fixture.sensitive':'synthetic-only'})
        self.assertEqual(json.loads(base64.b64decode(result['EHM_INSPECTOR_ARGUMENTS'])),['--server.port=12345'])
    def test_database_helper_uses_spring_resolved_values_not_guessed_env_aliases(self):
        settings={'spring.datasource.url':'jdbc:fixture:isolated','spring.datasource.password':'synthetic-only','ehm.persistence.schema':'fixture'}
        output=module.database_environment({'JAVA_TOOL_OPTIONS':'-Dfixture.only=true'},settings,'PROJECT_TEST')
        self.assertEqual(output['SPRING_DATASOURCE_URL'],'jdbc:fixture:isolated')
        self.assertEqual(output['SPRING_DATASOURCE_PASSWORD'],'synthetic-only')
        self.assertEqual(output['EHM_PERSISTENCE_SCHEMA'],'fixture');self.assertNotIn('JAVA_TOOL_OPTIONS',output)
    def test_inspector_does_not_load_application_components_or_auto_configuration(self):
        text=(path.parent/'SpringConfigInspector.java').read_text()
        self.assertIn('WebApplicationType.NONE',text)
        self.assertNotIn('@EnableAutoConfiguration',text);self.assertNotIn('@ComponentScan',text)
    def test_public_packaging_rejects_local_h2_and_accounts(self):
        text=(path.parent.parent/'prepare-simulation-release.py').read_text()
        for value in ['accounts.private.json','BOOT-INF/lib/h2-','ehm-server.env',"'.local'"]:
            self.assertIn(value,text)

if __name__=='__main__':unittest.main()

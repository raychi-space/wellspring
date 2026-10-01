import { writeFileSync } from 'node:fs';
const command=process.argv[2];
if(!['status','export','restore','retry'].includes(command))throw new Error('Use status|export|restore|retry [export-file]');
const base=process.env.RAYCHI_API_URL??'http://127.0.0.1:8080';
let cookie='';let csrf:{token:string;headerName:string};
async function call(path:string,method='GET',body?:unknown){
 const response=await fetch(base+path,{method,headers:{Cookie:cookie,...(csrf?{[csrf.headerName]:csrf.token}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(150000)});
 const updated=response.headers.getSetCookie().find(v=>v.startsWith('RAYCHI_SESSION='));if(updated)cookie=updated.split(';')[0];
 if(!response.ok)throw new Error(`Request failed: HTTP ${response.status}`);return response.json();
}
csrf=await call('/api/v1/auth/csrf');
await call('/api/v1/auth/login','POST',{username:process.env.RAYCHI_ADMIN_USER,password:process.env.RAYCHI_ADMIN_PASSWORD});
csrf=await call('/api/v1/auth/csrf');
const result=await call('/api/v1/admin/search/'+command,['restore','retry'].includes(command)?'POST':'GET');
if(command==='export') {const file=process.argv[3];if(!file)throw new Error('Provide a private export file');writeFileSync(file,JSON.stringify(result),{mode:0o600,flag:'wx'});console.log(JSON.stringify({exported:result.entries.length}));}else console.log(JSON.stringify(result));

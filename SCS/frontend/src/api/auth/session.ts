/** Restore the server session established by the registered cross-domain callback. */
const API_BASE=(import.meta.env.VITE_API_BASE || '/api/v1').replace(/\/$/,'')
export async function bootstrapSession():Promise<boolean>{
  try{return (await fetch(`${API_BASE}/auth/me`,{credentials:'same-origin',cache:'no-store'})).ok}
  catch{return false}
}

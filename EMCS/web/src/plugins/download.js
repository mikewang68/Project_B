import axios from 'axios'
import { ElLoading, ElMessage } from 'element-plus'
import { saveAs } from 'file-saver'
import { getToken } from '@/utils/auth'
import errorCode from '@/utils/errorCode'
import { blobValidate } from '@/utils/ruoyi'

const baseURL = import.meta.env.VITE_APP_BASE_API
let downloadLoadingInstance;

export default {
  name(name, isDelete = true) {
    var url = baseURL + "/common/download?fileName=" + encodeURIComponent(name) + "&delete=" + isDelete
    axios({
      method: 'get',
      url: url,
      responseType: 'blob',
      headers: { 'Authorization': 'Bearer ' + getToken() }
    }).then((res) => {
      const isBlob = blobValidate(res.data);
      if (isBlob) {
        const blob = new Blob([res.data])
        this.saveAs(blob, decodeURIComponent(res.headers['download-filename']))
      } else {
        this.printErrMsg(res.data);
      }
    })
  },
  resource(resource) {
    var url = baseURL + "/common/download/resource?resource=" + encodeURIComponent(resource);
    axios({
      method: 'get',
      url: url,
      responseType: 'blob',
      headers: { 'Authorization': 'Bearer ' + getToken() }
    }).then((res) => {
      const isBlob = blobValidate(res.data);
      if (isBlob) {
        const blob = new Blob([res.data])
        this.saveAs(blob, decodeURIComponent(res.headers['download-filename']))
      } else {
        this.printErrMsg(res.data);
      }
    })
  },
  zip(url, name) {
    var url = baseURL + url
    downloadLoadingInstance = ElLoading.service({ text: "正在下载数据，请稍候", background: "rgba(0, 0, 0, 0.7)", })
    axios({
      method: 'get',
      url: url,
      responseType: 'blob',
      headers: { 'Authorization': 'Bearer ' + getToken() }
    }).then((res) => {
      const isBlob = blobValidate(res.data);
      if (isBlob) {
        const blob = new Blob([res.data], { type: 'application/zip' })
        this.saveAs(blob, name)
      } else {
        this.printErrMsg(res.data);
      }
      downloadLoadingInstance.close();
    }).catch((r) => {
      console.error(r)
      ElMessage.error('下载文件出现错误，请联系管理员！')
      downloadLoadingInstance.close();
    })
  },
  // REQ-059/062：带认证的 JSON POST blob；保留响应签名与文件名，错误包不落盘。
  async postBlob(url, data, fallbackName = 'energy-report.xlsx') {
    downloadLoadingInstance = ElLoading.service({ text: "正在生成报表，请稍候", background: "rgba(0, 0, 0, 0.7)" })
    try {
      const res = await axios({
        method: 'post', url: baseURL + url, data, responseType: 'blob',
        headers: { 'Authorization': 'Bearer ' + getToken(), 'Content-Type': 'application/json;charset=utf-8' }
      })
      if (!blobValidate(res.data)) {
        await this.printErrMsg(res.data)
        throw new Error('报表接口返回业务错误')
      }
      const disposition = res.headers['content-disposition'] || ''
      const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
      const ordinary = disposition.match(/filename="?([^";]+)"?/i)?.[1]
      const filename = encoded ? decodeURIComponent(encoded) : (ordinary || fallbackName)
      this.saveAs(new Blob([res.data]), filename)
      return { filename, signature: res.headers['x-report-signature'] || null, headers: res.headers }
    } catch (error) {
      if (error?.response?.data instanceof Blob) await this.printErrMsg(error.response.data)
      throw error
    } finally { downloadLoadingInstance.close() }
  },
  async getBlob(url, fallbackName = 'energy-report.xlsx') {
    downloadLoadingInstance = ElLoading.service({ text: "正在下载归档，请稍候", background: "rgba(0, 0, 0, 0.7)" })
    try {
      const res = await axios({ method: 'get', url: baseURL + url, responseType: 'blob', headers: { 'Authorization': 'Bearer ' + getToken() } })
      if (!blobValidate(res.data)) { await this.printErrMsg(res.data); throw new Error('归档接口返回业务错误') }
      const disposition = res.headers['content-disposition'] || ''
      const filename = disposition.match(/filename="?([^";]+)"?/i)?.[1] || fallbackName
      this.saveAs(new Blob([res.data]), filename)
      return { filename, signature: res.headers['x-report-signature'] || null, headers: res.headers }
    } catch (error) {
      if (error?.response?.data instanceof Blob) await this.printErrMsg(error.response.data)
      throw error
    } finally { downloadLoadingInstance.close() }
  },
  saveAs(text, name, opts) {
    saveAs(text, name, opts);
  },
  async printErrMsg(data) {
    const resText = await data.text();
    const rspObj = JSON.parse(resText);
    const errMsg = errorCode[rspObj.code] || rspObj.msg || rspObj.detail || errorCode['default']
    ElMessage.error(errMsg);
  }
}

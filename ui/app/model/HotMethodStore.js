import http from 'utils/http';

export default class HotMethodStore {
  constructor(url) {
    this.nodes = [];
    this.methodLookup = {};
    this.url = url;
  }

  deDup(root, topLevel) {
    //input: {k: {data:{}, chld:{}},...]
    return Object.entries(root).reduce((accumNodes, [k, v]) => {
      if (k !== 'method_lookup') {
        let key = v['data'][0];      //method id
        if (!topLevel) {
          key = key + ':' + v['data'][1];       //add line number if this layer is top layer of hot-method tree
        }
        if (accumNodes[key]) {
          accumNodes[key][2].push([k, v]);        //[data, chld, parts]
          accumNodes[key][0][2] = accumNodes[key][0][2] + v['data'][2];       //adding up the sample count
        } else {
          accumNodes[key] = [v['data'], undefined, [[k, v]]];        //[data, chld, parts]
        }
      }
      return accumNodes;
    }, {});
    //output: {name/name:line: [data*, chld*, part]} where parts = [[k, v]...]], data contains aggregated data of same name:line nodes, chld is undefined to populated later
  }

  flatten(nodes, topLevel, bodyRoot) {
    const deDupNodes = this.deDup(nodes, topLevel);
    const curLayerIds = [];
    Object.entries(deDupNodes).forEach(([k, v]) => {
      this.nodes.push(v);
      const vIndex = this.nodes.length - 1;
      curLayerIds.push(vIndex);
      //Try next layer for this aggregated/deDuped node
        const nextLayerNodes = {};
        if(!topLevel) {
          v[2].filter(([k, v]) => v['chld']).forEach(([k, v]) => {
            if (Object.keys(v['chld']).length === 1) {
              Object.values(v['chld'])[0]['data'][2] = v['data'][2];
              Object.assign(nextLayerNodes, v['chld']);
            } else {
              console.log('hotMethod node should never happen more than one child/hotMethod parent, this has : ', v['chld']);
            }
          });
        } else {
          v[2].forEach(([k, v]) => {
              Object.assign(nextLayerNodes, {k:v});
          });
        }
        this.nodes[vIndex][1] = this.flatten(nextLayerNodes, false, false);
        if (!v[1] && bodyRoot) {        //if no part has a child field and if this is the body's root then it is a leaf node. Also nodes at bodyRoot level are guaranteed to have their children in the response
          v[1] = [];
        }
        if (this.nodes[vIndex][1] && this.nodes[vIndex].length === 3) {
          this.nodes[vIndex].pop();        //if chld is populated then remove parts
        }
    });
    return curLayerIds;
  }

  getChildrenAsync(uniqueId) {
    //if exists then return the children
    if (uniqueId >= this.nodes.length) { // never supposed to be true
      return [];
    }
    if (this.nodes[uniqueId] && this.nodes[uniqueId][1]) {
      return Promise.resolve(this.nodes[uniqueId][1]);
    }
    //else fetch from api
    const body = (uniqueId === -1) ? [] : this.nodes[uniqueId][2].map(([k,v])=>parseInt(k));    //body contains the parts of the aggregated node at uniqueId
    return http.post(this.url, body).then(
      resp => {
        if (uniqueId === -1) {
          this.nodes[uniqueId] = [null, undefined, Object.entries(resp).filter(([k, v]) => k !== 'method_lookup')];
        }
        this.nodes[uniqueId][1] = this.flatten(resp, uniqueId === -1, true);
        if (this.nodes[uniqueId][1] && this.nodes[uniqueId].length === 3) {
          this.nodes[uniqueId].pop();        //if chld is populated then remove parts
        }
        Object.assign(this.methodLookup, resp['method_lookup']);
        return this.nodes[uniqueId][1];
      }
    );
  }

  getNameWithArgs(uniqueId) {
    return uniqueId + ' ' + this.methodLookup[this.nodes[uniqueId][0][0]] + ' ' + this.nodes[uniqueId][0][1];
  }

  getSampleCount(uniqueId) {
    return this.nodes[uniqueId][0][2];
  }

  getChildren(uniqueId) {
    if (this.nodes[uniqueId]) {
      return this.nodes[uniqueId][1];
    } else {
      return [];
    }
  }
}

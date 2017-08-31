import React from 'react';
import { Link, withRouter } from 'react-router';
import { Chart } from 'react-google-charts';
import $ from 'jquery';
import Rainbow from 'rainbowvis.js';
import moment from 'moment';

import styles from './SubProfileStatComponent.css';

class SubProfileStatComponent extends React.Component {
  constructor (props) {
    super(props);
    this.state = {
      stat: null
    }
    this.tracesGrpByCov = {};
    this.selCov = null;
    this.handleTraceSelect = this.handleTraceSelect.bind(this);

    var rainbow = new Rainbow();
    rainbow.setSpectrum("#3f51b5", "#ff4081");
    this.traceChart = {
      rainbow: rainbow,
      cols: [
        {label: "Coverage", type: "number"},        
        {label: "Traces", type: "number"},
        {role: 'tooltip', type: 'string'},
        {role: 'style', type: 'string'},
      ],
      opts: {
        axisTitlesPosition: "in",
        hAxis: {minValue: 0, maxValue: 100, gridlines: {count: 11}},
        vAxis: {ticks:[], baseline: 0, viewWindow: {max: 0, min: 0}, baselineColor: 'none'},
        legend: 'none',
        chartArea: {left: 20, top: 10, width:'96%', height: 20},
        pointSize: 20,
        backgroundColor: "#f8f8f8",
      },
      rows: null,
      events: [{
        eventName: 'select',
        callback: this.handleTraceSelect
      }]
    };

    //Some keys are commented in worksamplechart for errored samples because they are missing in response. Enable once response json contains errored samples
    this.workSampleChart = {
      cols: [
        {label: "Work Type", type: "string"},        
        {label: "Samples", type: "number"},
        // {label: "Errored Samples", type: "number"},
        {role: "annotation", type: "string"},
      ],
      opts: {
        backgroundColor: "#f8f8f8",
        // isStacked: true,
        // legend: 'top',
        chartArea: {left: 10},
      },
      rows: null,
    }
  }

  handleTraceSelect(chart) {
    this.selCov = chart.chart.getSelection()[0] ? chart.chart.getSelection()[0].row : null;
    if(this.tracesGrpByCov && Object.keys(this.tracesGrpByCov).length && this.selCov !== null) {
      let cov = this.traceChart.rows[this.selCov][0];
      let content = "<div style='color: #b53f49; padding-left: 16px'>Traces with coverage=" + cov + "%</div><div class='mdl-grid'>";
      let traces = this.tracesGrpByCov[cov];
      for(let i = 0;i<traces.length;i++) {
        content += "<div class='mdl-cell mdl-cell--col-4'>" + traces[i] + "</div>";
      }
      $(".covDetail").html(content + "</div>");
    } else {
      $(".covDetail").empty();
    }
  }

  render () {
    const s = this.state.stat ? this.state.stat : this.props.defaultStat;
    if(!s) {
      return null;
    } else {
      this.workSampleChart.rows = Object.keys(s.samp).map(k => [k, s.samp[k], k]);

      this.tracesGrpByCov = {};
      s.tcov.forEach(t => {
        if(!this.tracesGrpByCov[t[1]]) {
          this.tracesGrpByCov[t[1]] = [];
        }
        this.tracesGrpByCov[t[1]].push(this.props.traces[t[0]]);
      });

      this.traceChart.rows = Object.keys(this.tracesGrpByCov).map(k => {
        this.tracesGrpByCov[k].sort();
        return [parseInt(k), 0,
          this.tracesGrpByCov[k].length + " traces",
          'point { fill-color:' + this.traceChart.rainbow.colorAt(parseInt(k)) + '}'];
      });

      return (
        <div className='statDetail' style={{ backgroundColor: '#f8f8f8'}}>
          <div className='mdl-grid' style={{borderBottom: '1px dashed #777'}}>
            <div className='mdl-cell mdl-cell--6-col' style={{fontSize: '20px'}}>
              <span style={{padding: '5px', color: 'white', borderTopLeftRadius: '5px', borderBottomLeftRadius: '5px', backgroundColor: '#b53f49'}}>IP</span>                
              <span style={{padding: '5px', color: 'white', borderTopRightRadius: '5px', borderBottomRightRadius: '5px'}} className='mdl-color--primary'>{s.basic[1]}</span>
            </div>
            <div className='mdl-cell mdl-cell--6-col' style={{fontSize: '20px'}}>
              <span style={{float: 'right', padding: '5px', color: 'white', borderTopRightRadius: '5px', borderBottomRightRadius: '5px'}} className='mdl-color--primary'>
                {moment(s.basic[3]).format("HH:mm:ss") + ' - ' + moment(s.basic[4]).format("HH:mm:ss")}
              </span>
              <span style={{float: 'right', padding: '5px', color: 'white', borderTopLeftRadius: '5px', borderBottomLeftRadius: '5px', backgroundColor: '#b53f49'}}>Interval</span>
            </div>
          </div>
          <div className='mdl-grid' style={{borderBottom: '1px dashed #777'}}>
            <div className='mdl-cell mdl-cell--6-col'>
              <div className='mdl-grid'>
                <div className='mdl-cell mdl-cell--6-col'>
                  <div style={{color: '#b53f49', lineHeight: 1}}>Status</div>
                  <div>{s.basic[0]}</div>
                </div>
                <div className='mdl-cell mdl-cell--6-col'>
                  <div style={{color: '#b53f49', lineHeight: 1}}>Rec Version</div>
                  <div>{s.rv}</div>
                </div>
              </div>
              <div className='mdl-grid'>
                <div className='mdl-cell mdl-cell--12-col'>
                  <div style={{color: '#b53f49', lineHeight: 1}}>JVM ID</div>
                  <div>{s.vm}</div>
                </div>
              </div>
            </div>

            {(this.workSampleChart.rows && this.workSampleChart.rows.length) ? (
              <div className='mdl-cell mdl-cell--6-col'>
                <Chart
                  chartType='BarChart'
                  options={this.workSampleChart.opts}
                  columns={this.workSampleChart.cols}
                  rows={this.workSampleChart.rows}
                  graph_id='WorkSampleChart'
                  width='100%'
                  height='100%'
                  chartPackages={['corechart', 'timeline']}
                />
              </div>
            ) : null}
          </div>

          {(this.traceChart.rows && this.traceChart.rows.length) ? (
            <div className='mdl-grid'>
              <div style={{padding: '12px 20px 0px 20px', fontSize: "12px", color: '#777'}}>Select any point to see traces having that coverage%</div>
              <div className='mdl-cell mdl-cell--12-col'>
                <Chart
                  chartType='ScatterChart'
                  chartEvents={this.traceChart.events}
                  options={this.traceChart.opts}
                  columns={this.traceChart.cols}
                  rows={this.traceChart.rows}
                  graph_id='TraceCoverageChart'
                  width='100%'
                  height='80px'
                  chartPackages={['corechart', 'timeline']}
                />
              </div>
              <div className='mdl-cell mdl-cell--12-col covDetail'></div>
            </div>
          ) : null}
        </div>
      );
    }
  }
}

export default SubProfileStatComponent;


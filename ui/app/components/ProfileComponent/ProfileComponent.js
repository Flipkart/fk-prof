import React from 'react';
import { Link, withRouter } from 'react-router';
import Modal from 'react-modal';
import { Chart } from 'react-google-charts';
import moment from 'moment';
import $ from 'jquery';
import Select from 'react-select';

import styles from './ProfileComponent.css';
import SubProfileStat from 'components/SubProfileStatComponent';

class ProfileComponent extends React.Component {
  constructor (props) {
    super(props);
    this.state = {
      collapse: true,
      collapseCount: 3,
      filterText: '',
      statsOpen: false,
      selIPValue: null // Unused. IP filtering is disabled because of issues keeping IP dropdown and timeline chart selections in sync
    };

    this.toggle = this.toggle.bind(this);
    this.setFilterText = this.setFilterText.bind(this);
    this.openStats = this.openStats.bind(this);
    this.closeStats = this.closeStats.bind(this);
    this.handleTimelineReady = this.handleTimelineReady.bind(this);
    this.handleTimelineSelect = this.handleTimelineSelect.bind(this);
    // this.onIPChange = this.onIPChange.bind(this); // Unused. IP filtering is disabled for now

    this.w_start = new Date(this.props.profile.start);
    this.w_end = new Date(this.w_start.getTime() + (this.props.profile.duration * 1000));
    this.timelineChart = null;
    this.subProfileStat = null;

    this.stats = null;
    this.statsView = null;
    this.ips = [];
    this.selStatIdx = 0;

    this.timeline = {
      cols: [
        {label: "Status", type: "string"},
        {label: "IP", type: "string"},
        {role: "tooltip", type: "string", p: {'html': true}},
        {label: "Start", type: "datetime"},
        {label: "End", type: "datetime"}
      ],
      rows: null,
      opts: {
        timeline: {
          showBarLabels: false
        },
        tooltip: {isHtml: true},
      },
      events: [{
        eventName: 'select',
        callback: this.handleTimelineSelect
      },{
        eventName: 'ready',
        callback: this.handleTimelineReady
      }]
    };
  }

  openStats() {
    this.setState({
      statsOpen: true
    });
  }

  closeStats() {
    this.setState({
      statsOpen: false
    });
  }

  setFilterText (e) {
    this.setState({ filterText: e.target.value });
  }

  toggle () {
    this.setState({ collapse: !this.state.collapse });
  }

  updateHeight() {
    if(this.timelineChart) {
      let timelineContainer = "#" + this.timelineChart.wrapper.getContainerId();
      let svgHeights = $(timelineContainer + " svg").map(function() { return $(this).attr("height"); }).toArray();
      if(svgHeights && svgHeights.length > 1) {
        let chartHeight = parseInt(svgHeights[svgHeights.length - 1]);
        if(chartHeight) {
          let newChartHeight = chartHeight + 60;
          this.timelineChart.wrapper.setOption("height", newChartHeight);
          this.timelineChart.wrapper.draw();
        }
      }
    }
  }

  render () {
    const tracesScore = this.props.tracesScore ? this.props.tracesScore : [];
    const isCollapsable = tracesScore.length > this.state.collapseCount;
    const isCollapsed = (isCollapsable && this.state.collapse);

    const list = isCollapsed
      ? tracesScore.slice(0, 3) :
        tracesScore.filter(t => t.name.indexOf(this.state.filterText) > -1);
    const selectedTraceName = this.props.params.traceName;
    const selectedProfile = this.props.location.query.profileStart;

    if(this.state.statsOpen) {
      if(!this.stats) {
        this.setupStats();
      }
      this.setupTimeline();
    }

    return (
      <div className={styles['main']}>
        <div className={styles.heading}>
          <h5>{this.props.heading}</h5>
          <div className={styles.profileInfoIcon} title="Stats" onClick={this.openStats}>
            <span className="material-icons">insert_chart</span>
          </div>
        </div>

        {isCollapsable && !isCollapsed && (
          <div className="mdl-textfield mdl-js-textfield" style={{padding: "8px 20px 4px 20px"}}>
            <input
              className="mdl-textfield__input"
              type="text"
              value={this.state.filterText}
              onChange={this.setFilterText}
              placeholder="Filter traces"
              style={{fontSize: "14px", padding: "4px"}}
            />
          </div>
        )}

        {list && list.length > 0 && (
          <div style={{padding: "4px 16px"}}>
            {list.map(l => (
              <div className={`${styles.itemContainer} ${(l.name === selectedTraceName && this.props.profile.start === selectedProfile) && styles.highlighted}`}
              key={l.name}>
                <Link
                  to={loc => ({ pathname: `/profiler/profile-data/${l.name}`, query: { ...loc.query, profileStart: this.props.profile.start, profileDuration: this.props.profile.duration }})}>
                  <span>(</span><span className="mdl-color-text--primary">{l.score}</span><span>)</span>
                  <span>&nbsp;</span>
                  <span>{l.name}</span>
                </Link>
              </div>
            ))}
          </div>
        )}

        {isCollapsable && (
          <div className={styles.center}>
            <button
              className="mdl-button mdl-js-button mdl-button--primary"
              style={{fontSize: "12px"}}
              onClick={this.toggle}
            >
              {this.state.collapse ? 'Show more' : 'Show Less'}
            </button>
          </div>
        )}

        {this.state.statsOpen && (
        <Modal
          isOpen={this.state.statsOpen}
          onRequestClose={this.closeStats}
          style={modalStyles}
          contentLabel={"Stats for " + this.props.heading}
        >
          <div style={{flex: 'none'}}>
            <h4 style={{margin: '8px 0 16px 0', paddingBottom: '4px', borderBottom: '1px dashed #777'}} className='mdl-color-text--primary'>
              {"Stats for aggregated profile of " + this.props.heading}
            </h4>
            <h6 style={{marginBottom: '8px', color: '#b53f49'}}>Timeline of recorded profiles in this window</h6>
            <div style={{fontSize: "12px", color: '#777'}}>Select any block to see stats associated with that IP</div>
          </div>
          {/*<div>
            <label htmlFor="ipSel">Filter by IP</label>
            <Select
              id="ipSel"
              clearable={true}
              options={this.ips}
              onChange={this.onIPChange}
              value={this.state.selIPValue}
              placeholder="Type to filter..."
            />
          </div>*/}
          <Chart
            chartType="Timeline"
            columns={this.timeline.cols}
            rows={this.timeline.rows}
            options={this.timeline.opts}
            graph_id="ProfileTimeline"
            width="100%"
            height="100%"
            chartEvents={this.timeline.events}
            chartPackages={['corechart', 'timeline']}
          />
          <SubProfileStat ref={c => { this.subProfileStat = c; }} traces={this.props.profile.traces} defaultStat={this.statsView ? this.statsView[this.selStatIdx] : null} />
        </Modal>
        )}
      </div>
    );
  }

  // onIPChange(val) {
  //   this.setState({
  //     selIPValue: val
  //   });
  // }

  setupStats() {
    this.stats = this.props.profile.profiles.map(p => {
      let stat = {};
      const p_start = new Date(this.w_start.getTime() + (p.start_offset * 1000));
      const p_end = new Date(p_start.getTime() + (p.duration * 1000));
      const ip = p.recorder_info ? p.recorder_info.ip : "No available recorder";
      const vm = p.recorder_info ? p.recorder_info.vm_id : "";
      const tooltip = '<div style="padding: 4px;"><div style="font-size: 14px;" class="mdl-color-text--primary">' + ip + '</div>'
        + '<div style="font-size: 10px;">' + moment(p_start).format("HH:mm:ss") + ' - ' + moment(p_end).format("HH:mm:ss") + '</div></div>';
      stat.basic = [p.status, ip, tooltip, p_start, p_end];
      stat.tcov = p.trace_coverage_map;
      stat.samp = p.sample_count;
      stat.rv = p.recorder_version;
      stat.vm = vm;
      return stat;
    });
    this.ips = this.stats.filter(s => s.basic[1]).map((s,i) => ({label: s.basic[1], value: i})).sort((a, b) => (a.label < b.label) ? -1 : ((a.label > b.label) ? 1 : 0));
  }

  setupTimeline() {
    this.statsView = this.state.selIPValue ? [this.stats[this.state.selIPValue.value]] : this.stats;
    this.selStatIdx = 0;
    this.timeline.rows = !this.statsView ? [] : this.statsView.map(s => s.basic);
    this.timeline.opts.hAxis = {
      minValue: this.w_start,
      maxValue: this.w_end,
      format: "HH:mm"
    };
  }

  handleTimelineReady(chart) {
    this.timelineChart = chart;
    this.updateHeight();
  }

  handleTimelineSelect(chart) {
    this.selStatIdx = chart.chart.getSelection()[0] ? chart.chart.getSelection()[0].row : null;
    this.renderStatDetail();
  }

  renderStatDetail() {
    if(this.subProfileStat && this.selStatIdx !== null) {
      this.subProfileStat.setState({
        stat: this.stats[this.selStatIdx]
      });
    }
  }
}

ProfileComponent.propTypes = {
  heading: React.PropTypes.string,
  traces: React.PropTypes.array,
  start: React.PropTypes.string,
  workTypeSummary: React.PropTypes.object,
};

const modalStyles  = {
  overlay: {
    zIndex: 10
  },
  content: {
    top: '50%',
    left: '50%',
    right: 'auto',
    bottom: 'auto',
    marginRight: '-50%',
    transform: 'translate(-50%, -50%)',
    height: '70%',
    width: '70%',
    display: 'flex',
    flexFlow: 'column',
    overflowY: 'scroll'
  }
};

export default withRouter(ProfileComponent);


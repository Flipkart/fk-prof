import React, { Component, PropTypes } from 'react';
import { connect } from 'react-redux';
import Select, {Creatable} from 'react-select';

import fetchProcsAction from 'actions/ProcActions';
import safeTraverse from 'utils/safeTraverse';

import styles from './ProcSelectComponent.css';

import fetchPolicyProcsAction from 'actions/PolicyProcActions';
import debounce from 'utils/debounce';

const noop = () => {};

class ProcSelectComponent extends Component {
  componentDidMount () {
    const { cluster, app } = this.props;
    if (cluster && app) {
      this.props.isPolicyPage ? this.props.getPolicyProcs(app)(cluster)() : this.props.getProcs(app)(cluster)();
    }
  }

  componentWillReceiveProps(nextProps) {
    const didAppChange = nextProps.app !== this.props.app;
    const didClusterChange = nextProps.cluster !== this.props.cluster;
    if (didAppChange || didClusterChange || nextProps.isPolicyPage !== this.props.isPolicyPage) {
      if (nextProps.isPolicyPage) {
        this.props.getPolicyProcs(nextProps.app)(nextProps.cluster)();
      } else {
        this.props.getProcs(nextProps.app)(nextProps.cluster)();
      }
    }
  }

  render() {
    const {onChange, procs, policyProcs} = this.props;
    const finalProcs = this.props.isPolicyPage ? policyProcs : procs;
    const procList = finalProcs.asyncStatus === 'SUCCESS'
      ? finalProcs.data.map(c => ({name: c})) : [];
    const valueOption = this.props.value && {name: this.props.value};
    return (
      <div>
        <label className={styles.label} htmlFor="proc">Process</label>
        {this.props.isPolicyPage &&
        <Creatable
          id="proc"
          clearable={false}
          options={procList}
          onChange={onChange || noop}
          labelKey="name"
          valueKey="name"
          onInputChange={debounce(this.props.getPolicyProcs(this.props.app)(this.props.cluster), 500)}
          isLoading={finalProcs.asyncStatus === 'PENDING'}
          value={valueOption}
          noResultsText={finalProcs.asyncStatus !== 'PENDING' ? 'No results found!' : 'Searching...'}
          placeholder="Type to search..."
          promptTextCreator={(label) => "Add process: " + label}
        />}
        {!this.props.isPolicyPage &&
        <Select
          id="proc"
          clearable={false}
          options={procList}
          onChange={onChange || noop}
          labelKey="name"
          valueKey="name"
          onInputChange={debounce(this.props.getProcs(this.props.app)(this.props.cluster), 500)}
          isLoading={finalProcs.asyncStatus === 'PENDING'}
          value={valueOption}
          noResultsText={finalProcs.asyncStatus !== 'PENDING' ? 'No results found!' : 'Searching...'}
          placeholder="Type to search..."
        />}
      </div>
    );
  }
}

const mapStateToProps = (state, ownProps) => ({
  procs: safeTraverse(state, ['procs', ownProps.cluster]) || {},
  policyProcs: safeTraverse(state, ['policyProcs', ownProps.cluster]) || {},
});

const mapDispatchToProps = dispatch => ({
  getProcs: app => cluster => event => dispatch(fetchProcsAction(app, cluster, event)),
  getPolicyProcs: app => cluster => event => dispatch(fetchPolicyProcsAction(app, cluster, event)),
});

ProcSelectComponent.propTypes = {
  app: PropTypes.string,
  cluster: PropTypes.string,
  procs: PropTypes.object.isRequired,
  policyProcs: PropTypes.object.isRequired,
  getProcs: PropTypes.func.isRequired,
  getPolicyProcs: PropTypes.func.isRequired,
  onChange: PropTypes.func,
  value: PropTypes.string,
  isPolicyPage: PropTypes.bool.isRequired,
};

export default connect(mapStateToProps, mapDispatchToProps)(ProcSelectComponent);
